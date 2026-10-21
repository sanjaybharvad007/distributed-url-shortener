# Architecture

## Goals and non-goals

**Goals**

- Redirects are the overwhelming majority of traffic (read:write ≈ 100:1 or higher). The redirect path must
  be cheap, cache-friendly and independently scalable from link management.
- No single point of failure in the request path; every tier scales horizontally.
- Sub-10 ms p99 for cache hits, < 50 ms p99 for cache misses within one region.
- Correctness guarantees: a short code is never reused, custom aliases are unique, deletes are visible
  across all cache shards within a bounded time.
- Everything observable: RED metrics per endpoint, cache hit ratio, shard health, rate-limit decisions,
  distributed traces across the Java and Go services.

**Non-goals (for now)**

- Multi-region active/active (DynamoDB Global Tables and Route 53 latency routing are the obvious next step).
- Vanity domains per customer.

## Components

```mermaid
flowchart LR
    C[Clients] --> ALB
    ALB -->|/api/v1/*| API[shortener-api<br/>Java 21 · Spring Boot 3]
    ALB -->|/{code}| R[redirector<br/>Go 1.26]
    API --> RING[(Redis ring<br/>3+ shards<br/>consistent hashing)]
    R --> RING
    API --> DDB[(DynamoDB<br/>short_urls)]
    R -->|cache miss| DDB
    R -->|click events| STREAM[(Redis Stream<br/>clicks)]
    STREAM -->|XREADGROUP| API
    API -->|ADD click_count| DDB
    API & R --> PROM[Prometheus]
    API & R --> OTEL[OTel Collector]
    PROM --> AM[Alertmanager]
    PROM --> GRAF[Grafana]
```

### shortener-api (Java, control plane)

Owns the lifecycle of a link.

- `POST /api/v1/urls` — validates the URL (scheme allow-list, length, no private/loopback hosts, no
  self-referencing loops), generates a Snowflake ID → Base62 code **or** reserves a custom alias with a
  DynamoDB conditional `PutItem`, writes the item, then **write-through caches** it on the owning Redis shard.
  Creates are idempotent per `(owner, normalized URL)` through the `url-hash-index` GSI.
- `DELETE /api/v1/urls/{code}` — writes a tombstone (`status=DELETED`, short TTL) so that DynamoDB no longer
  serves the link, then deletes the cache entry on the owning shard and writes a short-lived **negative cache**
  entry so that the redirector does not re-populate it from a stale replica.
- `GET /api/v1/urls/{code}/stats` — reads the item; `click_count` is eventually consistent (see below).
- Background **click consumer** — reads the `clicks` Redis Stream with a consumer group, aggregates by code in
  memory for up to 1 s / 500 events and applies a single atomic `ADD click_count :n` per code. Acknowledges
  only after the DynamoDB write succeeds, so events are at-least-once and the counter is monotonic.

### redirector (Go, data plane)

A deliberately tiny, dependency-light binary. Request flow for `GET /{code}`:

1. Validate the code shape (Base62, 4–32 chars). Reject early with 404 to avoid cache pollution.
2. Token-bucket rate limit per client IP (Lua script on the shard that owns the client key). Fail-open if
   Redis is unavailable, counting the decision in `ratelimit_decisions_total{result="failopen"}`.
3. Look up `url:{code}` on the shard chosen by the consistent-hash ring.
   - hit → 302 (or 301 for permanent links) with `Cache-Control: private, max-age=0`.
   - negative hit (`!`) → 404.
   - miss → `singleflight` the DynamoDB `GetItem` so a thundering herd on a cold key issues one read,
     populate the cache (positive TTL = min(remaining link TTL, 24 h); negative TTL = 60 s) and redirect.
4. Fire-and-forget a click event onto the `clicks` stream (bounded buffer; drops with a metric under back-pressure
   rather than slowing redirects).

### Data model (DynamoDB `short_urls`)

| Attribute | Type | Notes |
| --- | --- | --- |
| `short_code` (PK) | S | Base62 Snowflake or custom alias |
| `long_url` | S | normalized target |
| `url_hash` | S | `sha256(owner_id + "\n" + long_url)`; GSI `url-hash-index` (KEYS_ONLY) for idempotent creates |
| `owner_id` | S | API key owner; GSI `owner-index` (`owner_id`, `created_at`) |
| `created_at` | N | epoch millis |
| `expires_at` | N | epoch seconds, DynamoDB TTL attribute (absent = never) |
| `click_count` | N | eventually consistent counter |
| `status` | S | `ACTIVE` \| `DELETED` |
| `shard_id` | N | ring position at creation; used for ops tooling and rebalancing audits |

Partition key cardinality is extremely high (one item per code), so DynamoDB distributes the table evenly without
any custom sharding. Point-in-time recovery is enabled in Terraform.

### Consistent-hash ring

Both services construct the same ring from the same ordered list of node addresses:

- Hash function: MurmurHash3 x86-32 (implemented in both languages, verified against identical test vectors in
  `services/shortener-api/src/test/resources/ring-vectors.json` and `services/redirector/internal/hashring/testdata/ring-vectors.json`).
- 150 virtual nodes per physical node (`node-address#i`) → ~1–2 % load imbalance across 3 shards, ~1/N keys move on
  a membership change.
- Keys are routed by hashing the **logical key** (`url:{code}`), so adding a key prefix in one service and not the
  other is caught by the conformance test.

Why not Redis Cluster? Cluster mode gives the same key distribution (16384 slots) but requires cluster-aware clients
and makes Lua scripts touching multiple keys awkward. A client-side ring keeps every shard a plain Redis instance
(cheap, trivially replaceable with ElastiCache nodes), and lets us pin related keys (`url:`, `rl:`) to the same shard
on purpose. See [ADR 0002](adr/0002-consistent-hash-sharding.md).

### Rate limiting

Token bucket per `{api-key | ip}` stored in a Redis hash, refilled lazily by a Lua script
(`services/shortener-api/src/main/resources/lua/token_bucket.lua`, mirrored in Go). One round-trip, atomic, and
tolerant of clock skew because the script uses the Redis server's `TIME`. Response headers follow the IETF
`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` draft; 429s carry `Retry-After`.

### Resilience

- DynamoDB calls are wrapped in a Resilience4j circuit breaker + time limiter (Java) and bounded
  retries with decorrelated jitter (both). When the breaker is open the API returns 503 with `Retry-After`.
- The redirector keeps serving cached links if DynamoDB is down and keeps redirecting (without rate limits or
  click events) if Redis is down.
- Both services expose liveness (`/actuator/health/liveness`, `/healthz`) and readiness
  (`/actuator/health/readiness`, `/readyz`) probes; ECS/ALB use readiness to gate traffic.
- Graceful shutdown: in-flight requests are drained for up to 20 s and the click consumer flushes its buffer.

### Observability

| Signal | Java | Go |
| --- | --- | --- |
| Metrics | Micrometer → `/actuator/prometheus` | client_golang → `/metrics` |
| Traces | Micrometer Tracing (OTel bridge) → OTLP | OpenTelemetry SDK → OTLP |
| Logs | Logback JSON (`logstash-logback-encoder`) with trace IDs | `log/slog` JSON with trace IDs |

Key metrics: `http_server_requests_seconds` / `redirector_http_request_duration_seconds` (RED),
`cache_lookups_total{result=hit|miss|negative}`, `hashring_shard_requests_total{shard}`, `ratelimit_decisions_total`,
`click_events_published_total`, `click_events_dropped_total`, `click_consumer_lag_events`, `dynamodb_calls_total{op,result}`.

Alerting (`monitoring/alert-rules.yml`): availability SLO burn-rate (multi-window), p99 latency, cache hit ratio
collapse, any shard unreachable, click-stream lag, rate-limit fail-open. CloudWatch alarms in Terraform cover
ALB 5xx, target health, DynamoDB throttles and ECS CPU/memory.

### Capacity

Back-of-envelope for 10k redirects/s, 100 creates/s:

- Redis: one 2-byte-code → 100-byte URL entry ≈ 200 B with overhead. 100 M hot links ≈ 20 GB across 3 shards.
- DynamoDB on-demand: 100 WCU/s steady + ~10k reads/s only on cache-miss bursts (cold start / failover);
  normal miss rate < 5 % ⇒ < 500 RCU/s.
- Redirector: ~15k rps per 0.5 vCPU Fargate task in load tests; autoscaled on CPU and ALB request count.

### Security

- API keys (hashed at rest in prod; plain list in local profile) required for management endpoints; redirect is public.
- Open-redirect protection: only `http`/`https`, no credentials in URLs, no link targets inside the service's own
  domain, private/loopback/link-local hosts rejected.
- Containers run as non-root with read-only root filesystems; images scanned by Trivy in CI; IAM task roles grant
  only the DynamoDB actions each service needs; GitHub → AWS via OIDC (no static keys).
