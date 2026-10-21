// Package cache is the sharded Redis cache for code -> URL lookups.
package cache

// Key layout shared with shortener-api (com.urlshortener.api.cache.RedisKeys).
// Any change here must be mirrored there.
const (
	// NegativeSentinel is the value stored for codes known not to exist.
	NegativeSentinel = "!"
	// ClickStream is the Redis Stream carrying click events.
	ClickStream = "clicks"
)

// URLKey is the cache key for a short code.
func URLKey(code string) string { return "url:" + code }

// RateLimitKey is the token-bucket key for a client.
func RateLimitKey(client string) string { return "rl:" + client }
