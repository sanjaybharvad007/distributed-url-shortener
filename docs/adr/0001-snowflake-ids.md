# ADR 0001: Snowflake IDs encoded as Base62 for short codes

**Status:** Accepted · **Date:** 2026-10-20

## Context

Every short link needs a globally unique, URL-safe code. Options considered:

1. **Hash of the long URL** (e.g. first 7 chars of MD5/SHA-256) — collisions are rare but real at scale and
   require a read-before-write to detect; two users shortening the same URL would also share a code, which
   breaks per-owner stats and deletion.
2. **Database counter** (DynamoDB atomic `ADD`) — trivially unique and shortest possible codes, but every create
   pays a serialized write to one hot item (a single-partition bottleneck, ~1k writes/s) and codes are
   enumerable, which leaks creation volume.
3. **Pre-generated key service** (a pool of unused keys handed out in batches) — decoupled and fast, but adds a
   stateful service and a reclamation story.
4. **Snowflake-style IDs** — 41 bits of milliseconds since a custom epoch, 10 bits of worker ID, 12 bits of
   per-millisecond sequence. Unique without coordination, roughly time-ordered, 4096 IDs/ms/worker.

## Decision

Use Snowflake IDs generated inside `shortener-api` and encode them in Base62 (`0-9A-Za-z`), which yields
7–11 character codes. Worker IDs are injected per task (`APP_ID_WORKER_ID`, derived from the ECS task ID or
a stable hash of the hostname when not set). Custom aliases bypass the generator and are reserved with a
DynamoDB conditional write (`attribute_not_exists(short_code)`).

## Consequences

- Creates never read DynamoDB first; a single conditional `PutItem` is the whole write path.
- Codes are not enumerable in a useful way (worker and sequence bits scramble adjacent values) and encode no
  information about the target.
- Clock-skew protection: if the clock moves backwards the generator waits (up to 10 ms) or fails closed rather
  than risk duplicates. Worker IDs must be unique within the 1024 space — enforced by Terraform assigning
  distinct `APP_ID_WORKER_ID` values per task slot.
- Codes are longer than a counter would give (7+ vs 5 chars). Acceptable for the uniqueness and throughput gains.
