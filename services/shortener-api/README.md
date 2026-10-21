# shortener-api (Java 21 · Spring Boot 3)

Control-plane service: creates and manages short links, serves stats, runs the write-behind
click consumer, and serves redirects as a fallback.

```
src/main/java/com/urlshortener/api
├── cache/        RedisRing (sharded Lettuce connections), UrlCache (look-aside + negative cache), key layout
├── click/        ClickEvent, ClickPublisher (XADD), ClickConsumer (XREADGROUP → batched DynamoDB ADD)
├── config/       AppProperties, InfrastructureConfig (Redis, DynamoDB, Snowflake), WebConfig (filters, OpenAPI)
├── hashring/     MurmurHash3 + ConsistentHashRing (shared semantics with the Go redirector)
├── id/           SnowflakeIdGenerator, Base62
├── ratelimit/    Lua token bucket on the owning shard, fail-open
├── repository/   DynamoDbShortUrlRepository (conditional writes, GSIs, cursors, circuit breaker + retry)
├── service/      UrlShortenerService (idempotent create, resolve, delete)
├── validation/   UrlValidator (scheme allow-list, SSRF/open-redirect guards, normalisation)
└── web/          REST controllers, filters (API key, rate limit), RFC 9457 errors, health + hashring endpoints
```

## Run

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local   # expects Redis on 6379-6381 and DynamoDB Local on 8000
```

Or from the repo root: `make up`.

## Test

```bash
mvn test                 # unit tests (no Docker needed)
mvn verify               # + Testcontainers integration tests (needs Docker)
```

## Configuration

Every `app.*` key in `application.yml` can be overridden by environment variable, e.g.
`APP_REDIS_NODES`, `APP_DYNAMODB_ENDPOINT`, `APP_API_KEYS`, `APP_ID_WORKER_ID`,
`APP_RATE_LIMIT_REQUESTS_PER_SECOND`, `APP_RATE_LIMIT_BURST`.
