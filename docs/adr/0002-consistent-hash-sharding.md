# ADR 0002: Client-side consistent hashing for the Redis cache tier

**Status:** Accepted · **Date:** 2026-10-20

## Context

Hot link data must fit in memory across several Redis nodes, and both the Java control plane and the Go data
plane must agree on which node holds a key, so a write-through cache populate in Java is visible to the Go
redirector and a delete in Java invalidates the right node.

Options:

1. **Modulo sharding** (`crc32(key) % N`) — simple, but adding a node remaps ~all keys, causing a full cache
   miss storm.
2. **Redis Cluster** — server-side slot ownership, redirection handled by cluster-aware clients. Robust, but
   multi-key Lua (used by the rate limiter) needs hash tags, and ElastiCache cluster-mode has different
   operational knobs; also hides the sharding decision from the application, making it harder to reason about
   and to demonstrate.
3. **Client-side consistent-hash ring** — each client hashes keys onto a ring of virtual nodes; a membership
   change moves only ~1/N of keys.

## Decision

Implement a consistent-hash ring in both services with identical semantics:

- Hash function **MurmurHash3 x86 32-bit**, chosen for speed and simple, well-documented reference code that is
  easy to port byte-for-byte.
- **150 virtual nodes** per physical node, labelled `{address}#{i}`; the ring is a sorted array of
  `(hash, node)` and lookups are a binary search (O(log V)).
- Keys are hashed by their full logical name (`url:{code}`, `rl:{client}`), and the two implementations are
  pinned together by a shared test-vector file that both test suites load.

## Consequences

- Adding or removing a shard moves ~1/N keys; the runbook requires deploying both services with the new node
  list together.
- Each shard is a plain standalone Redis (or an ElastiCache primary + replica), which keeps infrastructure
  simple and cheap.
- No automatic failover between shards at the client level: a down shard is a cache miss (and a rate-limit
  fail-open) for its keys, surfaced by the `RedisShardDown` alert; DynamoDB absorbs the misses.
- The ring is also exposed on an internal admin endpoint for debugging key placement.
