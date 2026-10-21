# ADR 0005: Distributed token-bucket rate limiting in Redis

**Status:** Accepted · **Date:** 2026-10-20

## Context

Both services run many replicas behind a load balancer; a per-instance limiter would let a client obtain
N× the intended quota. Options: fixed window (bursty at window edges), sliding log (O(n) memory per client),
sliding window counter (approximate), token bucket (exact, O(1) memory, naturally allows short bursts).

## Decision

A token bucket per client key (`rl:{apiKey|ip}`) stored as a Redis hash `{tokens, ts}`, updated by a Lua
script that runs atomically on the shard that owns the key:

```
refill = (now - ts) * rate
tokens = min(capacity, tokens + refill)
allowed = tokens >= cost
if allowed then tokens -= cost end
EXPIRE key ceil(capacity / rate) * 2
return {allowed, tokens, retry_after_ms}
```

`now` comes from the Redis server (`TIME`) so replicas with skewed clocks agree. The same script is embedded
in both services. Responses carry `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` and, on 429,
`Retry-After`.

## Consequences

- Exactly one Redis round-trip per request, no race conditions, O(1) memory per client, keys self-expire.
- If the owning shard is unavailable the limiter **fails open** (the request proceeds) and increments
  `ratelimit_decisions_total{result="failopen"}`; the `RateLimitFailOpen` alert surfaces prolonged exposure.
  Failing closed would turn a cache outage into a full outage, which is the worse trade for a redirect service.
- Limits are configured per service: management API defaults to 20 rps / burst 40 per API key; redirector
  defaults to 200 rps / burst 400 per IP.
