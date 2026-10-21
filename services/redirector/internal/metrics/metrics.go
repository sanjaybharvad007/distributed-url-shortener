// Package metrics defines the Prometheus instruments exported by the redirector.
package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// Metrics groups every instrument so they can be injected rather than global.
type Metrics struct {
	RequestDuration *prometheus.HistogramVec
	CacheLookups    *prometheus.CounterVec
	ShardRequests   *prometheus.CounterVec
	ShardErrors     *prometheus.CounterVec
	RateLimit       *prometheus.CounterVec
	DynamoCalls     *prometheus.CounterVec
	DynamoDuration  prometheus.Histogram
	ClicksPublished prometheus.Counter
	ClicksDropped   prometheus.Counter
	ClickQueueDepth prometheus.Gauge
	SingleflightHit prometheus.Counter
}

// New registers the instruments on reg (prometheus.DefaultRegisterer when nil).
func New(reg prometheus.Registerer) *Metrics {
	if reg == nil {
		reg = prometheus.DefaultRegisterer
	}
	f := promauto.With(reg)
	return &Metrics{
		RequestDuration: f.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "redirector_http_request_duration_seconds",
			Help:    "Latency of HTTP requests by route and status.",
			Buckets: []float64{.0005, .001, .0025, .005, .01, .025, .05, .1, .25, .5, 1},
		}, []string{"route", "status"}),
		CacheLookups: f.NewCounterVec(prometheus.CounterOpts{
			Name: "cache_lookups_total",
			Help: "URL cache lookups by result (hit, miss, negative, error).",
		}, []string{"result"}),
		ShardRequests: f.NewCounterVec(prometheus.CounterOpts{
			Name: "hashring_shard_requests_total",
			Help: "Redis commands issued per shard.",
		}, []string{"shard"}),
		ShardErrors: f.NewCounterVec(prometheus.CounterOpts{
			Name: "hashring_shard_errors_total",
			Help: "Redis command failures per shard.",
		}, []string{"shard"}),
		RateLimit: f.NewCounterVec(prometheus.CounterOpts{
			Name: "ratelimit_decisions_total",
			Help: "Rate limit decisions by result (allowed, denied, failopen).",
		}, []string{"result"}),
		DynamoCalls: f.NewCounterVec(prometheus.CounterOpts{
			Name: "dynamodb_calls_total",
			Help: "DynamoDB calls by operation and result.",
		}, []string{"op", "result"}),
		DynamoDuration: f.NewHistogram(prometheus.HistogramOpts{
			Name:    "dynamodb_call_duration_seconds",
			Help:    "DynamoDB GetItem latency.",
			Buckets: prometheus.DefBuckets,
		}),
		ClicksPublished: f.NewCounter(prometheus.CounterOpts{
			Name:        "click_events_published_total",
			Help:        "Click events written to the Redis stream.",
			ConstLabels: prometheus.Labels{"source": "redirector"},
		}),
		ClicksDropped: f.NewCounter(prometheus.CounterOpts{
			Name:        "click_events_dropped_total",
			Help:        "Click events dropped because the buffer was full or Redis failed.",
			ConstLabels: prometheus.Labels{"source": "redirector"},
		}),
		ClickQueueDepth: f.NewGauge(prometheus.GaugeOpts{
			Name: "click_events_queue_depth",
			Help: "Click events waiting in the in-memory buffer.",
		}),
		SingleflightHit: f.NewCounter(prometheus.CounterOpts{
			Name: "singleflight_coalesced_total",
			Help: "DynamoDB lookups coalesced behind an in-flight request for the same code.",
		}),
	}
}
