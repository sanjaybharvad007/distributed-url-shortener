# Distributed URL Shortener

[![CI](https://github.com/sanjaybharvad007/distributed-url-shortener/actions/workflows/ci.yml/badge.svg)](https://github.com/sanjaybharvad007/distributed-url-shortener/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Go 1.26](https://img.shields.io/badge/Go-1.26-00ADD8)
![License](https://img.shields.io/badge/license-MIT-green)

A production-grade, horizontally scalable URL-shortening platform built the way a large
e-commerce or cloud company would build it: a **Java (Spring Boot) control-plane API** for
creating and managing links, a **Go data-plane redirector** for the hot redirect path,
**DynamoDB** as the system of record, **Redis** as a sharded cache fronted by a
**consistent-hash ring**, **token-bucket rate limiting**, full **observability**
(Prometheus, Grafana, OpenTelemetry, CloudWatch alarms), **Terraform-managed AWS
infrastructure** (ECS Fargate, ALB, DynamoDB, ElastiCache) and a **GitHub Actions CI/CD
pipeline** that builds, tests, scans and deploys every change.

```
                 ┌────────────────────────────── AWS ───────────────────────────────┐
                 │                                                                  │
   clients ──►  ALB ──┬──► shortener-api (Java 21 / Spring Boot 3)  ──┐              │
                 │    │      POST /api/v1/urls, GET /api/v1/urls/{code}/stats       │
                 │    │                                               ▼              │
                 │    └──► redirector (Go 1.26)  ───────────►  Redis ring (3 shards)│
                 │           GET /{code} → 302                 consistent hashing   │
                 │                     │   miss                    │                │
                 │                     └──────────────►  DynamoDB (short_urls)      │
                 │                     click events → Redis Stream → API consumer   │
                 │                                             (write-behind counts)│
                 │   Prometheus ◄── /metrics ──┐  OTLP traces ──► collector          │
                 │   Alertmanager ◄── rules    │  CloudWatch alarms ──► SNS          │
                 └──────────────────────────────────────────────────────────────────┘
```

## Highlights

| Concern | Implementation |
| --- | --- |
| Unique short codes | Snowflake-style 64-bit IDs (41-bit time, 10-bit worker, 12-bit sequence) encoded as Base62 — no coordination, no DB round-trip; custom aliases via DynamoDB conditional writes |
| Sharded caching | Consistent-hash ring (MurmurHash3, 150 virtual nodes per shard) implemented identically in Java and Go and verified against shared test vectors, so both services agree on key placement |
| Persistence | DynamoDB with TTL-based expiry, point-in-time recovery, GSIs for owner lookups and idempotent de-duplication |
| Hot path | Go redirector: Redis hit → `302` in sub-millisecond; miss → DynamoDB with single-flight de-dup and negative caching; click events published asynchronously to a Redis Stream |
| Rate limiting | Atomic token-bucket in Redis (Lua), per API key / IP, standard `RateLimit-*` + `Retry-After` headers, fail-open with metrics when Redis is unavailable |
| Resilience | Resilience4j circuit breakers + time limiters around DynamoDB, bounded retries with jitter, graceful shutdown, health/readiness probes |
| Observability | Micrometer + Prometheus metrics, OpenTelemetry tracing, structured JSON logs, Grafana dashboards, SLO-based Prometheus alert rules, CloudWatch alarms |
| Testing | JUnit 5 + Testcontainers (Redis, DynamoDB Local) integration tests, Go unit tests with race detector, cross-language hash-ring conformance tests, k6 load tests |
| Delivery | GitHub Actions: lint → unit/integration tests → container build → Trivy scan → push to ECR (OIDC, no long-lived keys) → ECS rolling deploy |
| Infrastructure | Terraform: VPC, ECS Fargate, ALB, DynamoDB, ElastiCache, ECR, IAM, CloudWatch alarms, SNS |

## Repository layout

```
.
├── services/
│   ├── shortener-api/     Java 21 / Spring Boot 3 control-plane API
│   └── redirector/        Go 1.26 data-plane redirect service
├── infra/terraform/       AWS infrastructure as code
├── monitoring/            Prometheus, Alertmanager, Grafana provisioning
├── loadtest/k6/           Load-test scenarios
├── docs/                  Architecture, runbook, ADRs
├── .github/workflows/     CI and CD pipelines
├── docker-compose.yml     Full local stack (3 Redis shards, DynamoDB Local, both services, Prometheus, Grafana)
└── Makefile
```

## Quick start (local)

Prerequisites: Docker + Docker Compose, Java 21, Maven 3.9, Go 1.26.

```bash
make up             # builds both services and starts the full stack
make smoke          # creates a link, follows the redirect, fetches stats
```

Then:

| Endpoint | URL |
| --- | --- |
| API (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Redirector | http://localhost:8081/{code} |
| Prometheus | http://localhost:9090 |
| Grafana (admin / admin) | http://localhost:3000 |
| Alertmanager | http://localhost:9093 |

### Create a short link

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key' \
  -d '{"longUrl":"https://aws.amazon.com/dynamodb/","ttlSeconds":86400}' | jq
```

```json
{
  "shortCode": "5kH2mQ8",
  "shortUrl": "http://localhost:8081/5kH2mQ8",
  "longUrl": "https://aws.amazon.com/dynamodb/",
  "createdAt": "2026-10-20T22:10:15.123Z",
  "expiresAt": "2026-10-21T22:10:15.123Z"
}
```

```bash
curl -i http://localhost:8081/5kH2mQ8          # HTTP/1.1 302 Found, Location: https://aws.amazon.com/dynamodb/
curl -s http://localhost:8080/api/v1/urls/5kH2mQ8/stats -H 'X-API-Key: dev-key' | jq
```

## API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/urls` | Create a short link (optional `customAlias`, `ttlSeconds`); idempotent per owner + URL |
| `GET` | `/api/v1/urls/{code}` | Fetch link metadata |
| `GET` | `/api/v1/urls/{code}/stats` | Click statistics |
| `DELETE` | `/api/v1/urls/{code}` | Soft-delete (tombstone + cache invalidation across the ring) |
| `GET` | `/api/v1/urls?owner=me` | List links for the caller |
| `GET` | `/{code}` | Redirect (served by the Go redirector; the Java API also serves it as a fallback) |

Errors follow RFC 9457 `application/problem+json`. Rate-limited requests return `429` with `Retry-After`.

Full details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/RUNBOOK.md](docs/RUNBOOK.md), [docs/adr](docs/adr).

## Running tests

```bash
make test            # Java unit + integration tests (Testcontainers) and Go tests with -race
make lint            # Spotless/Checkstyle for Java, golangci-lint + go vet for Go, terraform fmt/validate
make load-test       # k6 redirect scenario against the local stack
```

## Deploying to AWS

```bash
cd infra/terraform
terraform init
terraform apply -var 'environment=prod' -var 'alert_email=oncall@example.com'
```

The `deploy.yml` workflow assumes an IAM role via GitHub OIDC, builds and pushes images to ECR,
registers new ECS task definitions and triggers a rolling deployment with health-check gating.
See [docs/RUNBOOK.md](docs/RUNBOOK.md) for rollback, scaling and on-call procedures.

## Design decisions

Architecture Decision Records live in [docs/adr](docs/adr):

1. [Snowflake IDs + Base62 instead of hashing or a counter](docs/adr/0001-snowflake-ids.md)
2. [Consistent hashing for the Redis cache tier](docs/adr/0002-consistent-hash-sharding.md)
3. [Split control plane (Java) and data plane (Go)](docs/adr/0003-java-control-plane-go-data-plane.md)
4. [Write-behind click counting through a Redis Stream](docs/adr/0004-write-behind-click-counting.md)
5. [Token-bucket rate limiting in Redis](docs/adr/0005-token-bucket-rate-limiting.md)
6. [Spring Boot 4 migration (proposed)](docs/adr/0006-spring-boot-4-migration.md)

## License

MIT
