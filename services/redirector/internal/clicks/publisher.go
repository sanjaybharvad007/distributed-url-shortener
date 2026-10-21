// Package clicks publishes redirect events to the Redis Stream consumed by the
// Java API (write-behind click counting, ADR 0004).
package clicks

import (
	"context"
	"log/slog"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/urlshortener/redirector/internal/cache"
	"github.com/urlshortener/redirector/internal/metrics"
)

// Event is one redirect. Field names match com.urlshortener.api.click.ClickEvent.
type Event struct {
	Code         string
	Timestamp    time.Time
	UAClass      string
	ReferrerHost string
}

// Publisher buffers events in memory and writes them to the stream from a
// background goroutine so the request path never waits on Redis.
type Publisher struct {
	ring   *cache.Ring
	stream string
	maxLen int64
	buf    chan Event
	m      *metrics.Metrics
	log    *slog.Logger

	stop    chan struct{}
	stopped sync.WaitGroup
	once    sync.Once
}

// New starts a Publisher with the given buffer size.
func New(ring *cache.Ring, stream string, maxLen int64, buffer int, m *metrics.Metrics, log *slog.Logger) *Publisher {
	p := &Publisher{
		ring:   ring,
		stream: stream,
		maxLen: maxLen,
		buf:    make(chan Event, buffer),
		m:      m,
		log:    log,
		stop:   make(chan struct{}),
	}
	p.stopped.Add(1)
	go p.loop()
	return p
}

// Publish enqueues an event; drops (and counts) it if the buffer is full.
func (p *Publisher) Publish(e Event) {
	select {
	case p.buf <- e:
		p.m.ClickQueueDepth.Set(float64(len(p.buf)))
	default:
		p.m.ClicksDropped.Inc()
	}
}

func (p *Publisher) loop() {
	defer p.stopped.Done()
	for {
		select {
		case e := <-p.buf:
			p.write(e)
			p.m.ClickQueueDepth.Set(float64(len(p.buf)))
		case <-p.stop:
			// Drain what we can within the shutdown window.
			for {
				select {
				case e := <-p.buf:
					p.write(e)
				default:
					return
				}
			}
		}
	}
}

func (p *Publisher) write(e Event) {
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	client, shard := p.ring.Client(p.stream)
	err := client.XAdd(ctx, &redis.XAddArgs{
		Stream: p.stream,
		MaxLen: p.maxLen,
		Approx: true,
		Values: map[string]any{
			"code": e.Code,
			"ts":   strconv.FormatInt(e.Timestamp.UnixMilli(), 10),
			"ua":   e.UAClass,
			"ref":  e.ReferrerHost,
		},
	}).Err()
	if err != nil {
		p.ring.RecordError(shard)
		p.m.ClicksDropped.Inc()
		p.log.Warn("click event dropped", "code", e.Code, "err", err)
		return
	}
	p.m.ClicksPublished.Inc()
}

// Close stops the loop, flushing buffered events. Safe to call twice.
func (p *Publisher) Close() {
	p.once.Do(func() { close(p.stop) })
	p.stopped.Wait()
}
