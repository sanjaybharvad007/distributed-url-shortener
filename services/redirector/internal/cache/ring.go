package cache

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/urlshortener/redirector/internal/hashring"
	"github.com/urlshortener/redirector/internal/metrics"
)

// Ring routes commands to one of several standalone Redis shards using the
// consistent-hash ring shared with the Java API.
type Ring struct {
	ring    *hashring.Ring
	clients []*redis.Client
	labels  []string
	m       *metrics.Metrics
}

// NewRing connects to every node. Connections are lazy; failures surface per
// command so a down shard degrades only the keys it owns.
func NewRing(nodes []string, virtualNodes int, timeout time.Duration, m *metrics.Metrics) (*Ring, error) {
	hr, err := hashring.New(nodes, virtualNodes)
	if err != nil {
		return nil, err
	}
	r := &Ring{ring: hr, m: m}
	for i, n := range nodes {
		r.clients = append(r.clients, redis.NewClient(&redis.Options{
			Addr:            n,
			DialTimeout:     timeout * 2,
			ReadTimeout:     timeout,
			WriteTimeout:    timeout,
			PoolSize:        64,
			MinIdleConns:    4,
			MaxRetries:      0, // the caller decides; retries would hide shard problems
			ConnMaxIdleTime: 5 * time.Minute,
		}))
		r.labels = append(r.labels, strconv.Itoa(i))
	}
	return r, nil
}

// Shard returns the index of the node owning key.
func (r *Ring) Shard(key string) int { return r.ring.Index(key) }

// Client returns the Redis client for the shard owning key, plus the shard index.
func (r *Ring) Client(key string) (*redis.Client, int) {
	i := r.ring.Index(key)
	r.m.ShardRequests.WithLabelValues(r.labels[i]).Inc()
	return r.clients[i], i
}

// ClientAt returns the client for a shard index (health checks, tests).
func (r *Ring) ClientAt(i int) *redis.Client { return r.clients[i] }

// Size is the number of shards.
func (r *Ring) Size() int { return len(r.clients) }

// Nodes returns the configured node addresses.
func (r *Ring) Nodes() []string { return r.ring.Nodes() }

// RecordError counts a failure on a shard.
func (r *Ring) RecordError(shard int) { r.m.ShardErrors.WithLabelValues(r.labels[shard]).Inc() }

// Ping checks every shard, returning the number reachable and the first error.
func (r *Ring) Ping(ctx context.Context) (up int, firstErr error) {
	for i, c := range r.clients {
		if err := c.Ping(ctx).Err(); err != nil {
			if firstErr == nil {
				firstErr = fmt.Errorf("shard %d (%s): %w", i, r.ring.Nodes()[i], err)
			}
			continue
		}
		up++
	}
	return up, firstErr
}

// Close closes every client.
func (r *Ring) Close() error {
	var first error
	for _, c := range r.clients {
		if err := c.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}
