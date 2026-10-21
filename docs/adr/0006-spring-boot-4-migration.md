# ADR 0006: Migrate shortener-api to Spring Boot 4 / Spring Framework 7

**Status:** Proposed · **Date:** 2026-10-21

## Context

The container vulnerability gate (Trivy, CRITICAL/HIGH with a fix available) flags
`spring-webmvc 6.2.19` (CVE-2026-47884, CVE-2026-47890). Spring Framework 6.2 no longer receives open-source
patches; the fixes exist only in Spring Framework 7.0.9+, which requires Spring Boot 4.

Everything else in the image is on a maintained line (Jackson 2.21, Netty 4.1.137, httpcore5 5.4, Tomcat 10.1.61,
Alpine packages upgraded at build time).

## Decision

1. Short term: list the two advisories in `.trivyignore` with a review date so the gate stays meaningful for
   every other dependency. The entries remain visible in the Security tab.
2. Next: migrate to Spring Boot 4.1 on a branch. Known work items:
   - starter renames (`spring-boot-starter-web` → `spring-boot-starter-webmvc`), Tomcat 11, drop the Tomcat/Jackson pins
   - Jackson 3 (`tools.jackson.*`) in `DynamoDbShortUrlRepository` cursor codec and `ConsistentHashRingTest`
   - actuator package moves (`org.springframework.boot.health.contributor.*`) for the health indicators
   - `@MockBean` → `@MockitoBean`; springdoc 3.x; verify Resilience4j Boot 4 support; OTLP property renames
3. Remove the `.trivyignore` entries in the migration PR; CI's gate enforces it.

## Consequences

- The gate keeps blocking new fixable advisories while a known framework-level exception is tracked explicitly.
- The migration is a contained, reviewable change rather than an emergency patch.
