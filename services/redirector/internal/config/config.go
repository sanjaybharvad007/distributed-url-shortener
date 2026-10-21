// Package config loads redirector settings from the environment.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds every tunable of the redirector. All values come from
// REDIRECTOR_* environment variables with sensible defaults for local use.
type Config struct {
	Addr            string
	RedisNodes      []string
	VirtualNodes    int
	RedisTimeout    time.Duration
	PositiveTTL     time.Duration
	NegativeTTL     time.Duration
	DynamoEndpoint  string
	DynamoTable     string
	DynamoTimeout   time.Duration
	RateLimitEnable bool
	RateLimitRPS    float64
	RateLimitBurst  int64
	ClickStream     string
	ClickBuffer     int
	ClickMaxLen     int64
	TrustProxy      bool
	ShutdownTimeout time.Duration
	OTLPEndpoint    string
	TraceSampling   float64
	ServiceVersion  string
}

// Load reads the configuration from the environment.
func Load() (Config, error) {
	c := Config{
		Addr:            env("REDIRECTOR_ADDR", ":8081"),
		RedisNodes:      splitList(env("REDIRECTOR_REDIS_NODES", "localhost:6379,localhost:6380,localhost:6381")),
		VirtualNodes:    envInt("REDIRECTOR_VIRTUAL_NODES", 150),
		RedisTimeout:    envDuration("REDIRECTOR_REDIS_TIMEOUT", 150*time.Millisecond),
		PositiveTTL:     envDuration("REDIRECTOR_POSITIVE_TTL", 24*time.Hour),
		NegativeTTL:     envDuration("REDIRECTOR_NEGATIVE_TTL", 60*time.Second),
		DynamoEndpoint:  env("REDIRECTOR_DYNAMODB_ENDPOINT", ""),
		DynamoTable:     env("REDIRECTOR_DYNAMODB_TABLE", "short_urls"),
		DynamoTimeout:   envDuration("REDIRECTOR_DYNAMODB_TIMEOUT", 800*time.Millisecond),
		RateLimitEnable: envBool("REDIRECTOR_RATE_LIMIT_ENABLED", true),
		RateLimitRPS:    envFloat("REDIRECTOR_RATE_LIMIT_RPS", 200),
		RateLimitBurst:  int64(envInt("REDIRECTOR_RATE_LIMIT_BURST", 400)),
		ClickStream:     env("REDIRECTOR_CLICK_STREAM", "clicks"),
		ClickBuffer:     envInt("REDIRECTOR_CLICK_BUFFER", 10000),
		ClickMaxLen:     int64(envInt("REDIRECTOR_CLICK_MAXLEN", 1_000_000)),
		TrustProxy:      envBool("REDIRECTOR_TRUST_PROXY", true),
		ShutdownTimeout: envDuration("REDIRECTOR_SHUTDOWN_TIMEOUT", 20*time.Second),
		OTLPEndpoint:    env("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		TraceSampling:   envFloat("OTEL_TRACES_SAMPLER_ARG", 0.1),
		ServiceVersion:  env("SERVICE_VERSION", ""), // falls back to the build-time version in main
	}
	if len(c.RedisNodes) == 0 {
		return c, fmt.Errorf("REDIRECTOR_REDIS_NODES must list at least one node")
	}
	if c.VirtualNodes < 1 {
		return c, fmt.Errorf("REDIRECTOR_VIRTUAL_NODES must be >= 1")
	}
	if c.RateLimitRPS <= 0 || c.RateLimitBurst <= 0 {
		return c, fmt.Errorf("rate limit rps and burst must be positive")
	}
	return c, nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envFloat(key string, def float64) float64 {
	if v := os.Getenv(key); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}

func envBool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
