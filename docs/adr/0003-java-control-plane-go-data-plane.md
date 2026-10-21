# ADR 0003: Java control plane, Go data plane

**Status:** Accepted · **Date:** 2026-10-20

## Context

Traffic is heavily skewed: redirects outnumber link creations by two or more orders of magnitude, and the
redirect path is tiny (validate code → cache lookup → 302). Link management is comparatively rich: validation,
idempotency, aliases, ownership, stats, admin tooling, background consumers.

## Decision

Split the system into two deployables:

- **`shortener-api` (Java 21, Spring Boot 3)** for the control plane. Spring's ecosystem (validation, OpenAPI,
  Actuator, Micrometer, Resilience4j, Testcontainers) makes the feature-rich surface cheap to build and operate.
  It also runs the click-stream consumer and serves redirects as a fallback so the system is fully functional
  with a single deployable in small environments.
- **`redirector` (Go 1.26)** for the data plane. A static binary with a ~10 MB distroless image, ~20 MB RSS,
  no warm-up, and predictable tail latency without GC tuning. It scales independently and can be packed far
  more densely than JVM tasks.

Both share the DynamoDB schema, the Redis key layout and the consistent-hash ring, all pinned by conformance
tests and documented in `docs/ARCHITECTURE.md`.

## Consequences

- Two toolchains and two CI matrices. Mitigated with a monorepo, one Makefile and one compose stack.
- Shared logic (ring, key names, rate-limit script) exists twice. Kept small and verified by shared test
  vectors; any change to key layout must touch both and the conformance test fails otherwise.
- Clear ownership boundaries: the Go service never writes link data; the Java service is the only writer to
  DynamoDB and the cache (apart from the redirector's miss-populate with the same TTL rules).
