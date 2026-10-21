-- Atomic token-bucket rate limiter.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (burst), ARGV[2] = refill rate (tokens per second),
-- ARGV[3] = cost of this request
-- Returns {allowed(0|1), remaining_tokens, retry_after_ms, reset_seconds}
--
-- Uses the Redis server clock (TIME) so every replica of every service agrees
-- on "now" regardless of their own clock skew. Identical copy lives in the Go
-- redirector at internal/ratelimit/token_bucket.lua.

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) -- ms

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil then
  tokens = capacity
  ts     = now
end

local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_after_ms = math.ceil((cost - tokens) / rate * 1000)
end

-- Seconds until the bucket is full again (IETF RateLimit-Reset semantics).
local reset_seconds = math.ceil((capacity - tokens) / rate)

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, math.ceil(capacity / rate * 1000) * 2)

return { allowed, math.floor(tokens), retry_after_ms, reset_seconds }
