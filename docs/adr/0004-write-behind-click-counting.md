# ADR 0004: Write-behind click counting through a Redis Stream

**Status:** Accepted · **Date:** 2026-10-20

## Context

Every redirect should increment a per-link click counter. Doing a DynamoDB `UpdateItem` inline on each
redirect would (a) add 5–15 ms to the hot path, (b) cost one WCU per redirect — the dominant cost of the whole
system at scale, and (c) couple redirect availability to DynamoDB write availability.

## Decision

The redirector publishes a small event (`code`, `ts`, `ua-class`, `referrer-host`) to a Redis Stream
(`clicks`, `XADD MAXLEN ~ 1000000`) from a bounded in-memory buffer, off the request goroutine. The Java API
consumes the stream with a consumer group (`XREADGROUP`), aggregates events per code for up to 1 s or 500
events, writes one `ADD click_count :n` per code, then `XACK`s the batch. Pending-entry recovery (`XAUTOCLAIM`)
re-processes events from crashed consumers.

## Consequences

- Redirect latency and availability are independent of DynamoDB writes.
- Counting is at-least-once and eventually consistent (seconds). Duplicates after a consumer crash are bounded
  by the batch size; acceptable for analytics counters, documented in the API.
- The buffer in the redirector drops events under sustained back-pressure, counted in
  `click_events_dropped_total`; an alert fires if the drop rate is non-zero for 5 minutes.
- A Redis Stream, rather than Kinesis/SQS, keeps the local stack self-contained. The consumer is isolated
  behind an interface so swapping to Kinesis in AWS is a contained change.
