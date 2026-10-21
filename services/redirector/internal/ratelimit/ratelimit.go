// Package ratelimit implements a distributed token bucket on the Redis ring.
package ratelimit

import (
	"context"
	_ "embed"
	"strconv"

	"github.com/redis/go-redis/v9"

	"github.com/urlshortener/redirector/internal/cache"
	"github.com/urlshortener/redirector/internal/metrics"
)

//go:embed token_bucket.lua
var script string

// Decision is the outcome of a rate-limit check.
type Decision struct {
	Allowed    bool
	Limit      int64
	Remaining  int64
	ResetSecs  int64
	RetryAfter int64 // milliseconds; only meaningful when !Allowed
	FailedOpen bool
}

// Limiter evaluates the token bucket atomically on the shard owning the client key.
type Limiter struct {
	ring   *cache.Ring
	rps    float64
	burst  int64
	m      *metrics.Metrics
	script *redis.Script
}

// New builds a Limiter allowing rps sustained with a burst of burst.
func New(ring *cache.Ring, rps float64, burst int64, m *metrics.Metrics) *Limiter {
	return &Limiter{ring: ring, rps: rps, burst: burst, m: m, script: redis.NewScript(script)}
}

// Check consumes one token for client. On any Redis failure it fails open
// (ADR 0005) and records the fact.
func (l *Limiter) Check(ctx context.Context, client string) Decision {
	key := cache.RateLimitKey(client)
	rc, shard := l.ring.Client(key)
	res, err := l.script.Run(ctx, rc, []string{key},
		strconv.FormatInt(l.burst, 10),
		strconv.FormatFloat(l.rps, 'f', -1, 64),
		"1",
	).Int64Slice()
	if err != nil || len(res) < 4 {
		l.ring.RecordError(shard)
		l.m.RateLimit.WithLabelValues("failopen").Inc()
		return Decision{Allowed: true, Limit: l.burst, Remaining: l.burst, FailedOpen: true}
	}
	d := Decision{
		Allowed:    res[0] == 1,
		Limit:      l.burst,
		Remaining:  res[1],
		RetryAfter: res[2],
		ResetSecs:  res[3],
	}
	if d.Allowed {
		l.m.RateLimit.WithLabelValues("allowed").Inc()
	} else {
		l.m.RateLimit.WithLabelValues("denied").Inc()
	}
	return d
}
