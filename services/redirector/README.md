# redirector (Go 1.26)

Data-plane service: `GET /{code}` → `302`. Static binary, distroless image, no warm-up.

```
cmd/redirector/        main: wiring, graceful shutdown, OTLP tracing, container healthcheck probe
internal/
├── config/            REDIRECTOR_* environment configuration
├── hashring/          MurmurHash3 + consistent-hash ring (conformance-tested against the Java service)
├── cache/             Ring (sharded go-redis clients), URLCache (look-aside + negative cache), key layout
├── store/             DynamoDB reader with single-flight de-duplication and timeouts
├── ratelimit/         Lua token bucket on the owning shard, fail-open
├── clicks/            buffered async publisher to the `clicks` Redis Stream
├── metrics/           Prometheus instruments
└── server/            HTTP handlers, middleware, health probes
```

## Run

```bash
go run ./cmd/redirector          # expects Redis on 6379-6381 and DynamoDB Local on 8000
```

## Test

```bash
go test -race ./...              # uses miniredis; no Docker required
go test -bench=. ./internal/hashring/
```

## Request flow

1. Validate the code (`[0-9A-Za-z]{4,32}`) → 404 early.
2. Token bucket per client IP (Lua on the owning shard; fails open).
3. `GET url:{code}` on the owning shard → hit: 302 · negative: 404 · miss: DynamoDB (single-flight), populate, 302 / negative-cache, 404.
4. Enqueue a click event; a background goroutine `XADD`s it to the `clicks` stream.

## Endpoints

| Path | Purpose |
| --- | --- |
| `GET /{code}` | Redirect |
| `GET /healthz` | Liveness |
| `GET /readyz` | Readiness (≥1 Redis shard and DynamoDB reachable) |
| `GET /metrics` | Prometheus |
