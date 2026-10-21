package cache

import (
	"context"
	"errors"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/urlshortener/redirector/internal/metrics"
)

// Result of a cache lookup.
type Result int

// Lookup outcomes.
const (
	Miss     Result = iota // not cached; consult the store
	Hit                    // URL present
	Negative               // known not to exist
	Error                  // shard unreachable; treat as miss
)

// URLCache is a look-aside cache with negative entries.
type URLCache struct {
	ring        *Ring
	positiveTTL time.Duration
	negativeTTL time.Duration
	m           *metrics.Metrics
}

// NewURLCache builds the cache over ring.
func NewURLCache(ring *Ring, positiveTTL, negativeTTL time.Duration, m *metrics.Metrics) *URLCache {
	return &URLCache{ring: ring, positiveTTL: positiveTTL, negativeTTL: negativeTTL, m: m}
}

// Get looks up code.
func (c *URLCache) Get(ctx context.Context, code string) (string, Result) {
	key := URLKey(code)
	client, shard := c.ring.Client(key)
	v, err := client.Get(ctx, key).Result()
	switch {
	case errors.Is(err, redis.Nil):
		c.m.CacheLookups.WithLabelValues("miss").Inc()
		return "", Miss
	case err != nil:
		c.ring.RecordError(shard)
		c.m.CacheLookups.WithLabelValues("error").Inc()
		return "", Error
	case v == NegativeSentinel:
		c.m.CacheLookups.WithLabelValues("negative").Inc()
		return "", Negative
	default:
		c.m.CacheLookups.WithLabelValues("hit").Inc()
		return v, Hit
	}
}

// Set populates code -> url. TTL is capped by the link's own expiry (zero = none).
func (c *URLCache) Set(ctx context.Context, code, url string, expiresAt time.Time) {
	ttl := c.positiveTTL
	if !expiresAt.IsZero() {
		if remaining := time.Until(expiresAt); remaining <= 0 {
			ttl = time.Second
		} else if remaining < ttl {
			ttl = remaining
		}
	}
	key := URLKey(code)
	client, shard := c.ring.Client(key)
	if err := client.Set(ctx, key, url, ttl).Err(); err != nil {
		c.ring.RecordError(shard)
	}
}

// SetNegative records that code does not exist.
func (c *URLCache) SetNegative(ctx context.Context, code string) {
	key := URLKey(code)
	client, shard := c.ring.Client(key)
	if err := client.Set(ctx, key, NegativeSentinel, c.negativeTTL).Err(); err != nil {
		c.ring.RecordError(shard)
	}
}
