package config

import (
	"testing"
	"time"
)

func TestDefaults(t *testing.T) {
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.Addr != ":8081" || len(c.RedisNodes) != 3 || c.VirtualNodes != 150 {
		t.Fatalf("unexpected defaults %+v", c)
	}
	if c.RateLimitRPS != 200 || c.RateLimitBurst != 400 || !c.RateLimitEnable {
		t.Fatalf("unexpected rate-limit defaults %+v", c)
	}
}

func TestOverridesAndParsing(t *testing.T) {
	t.Setenv("REDIRECTOR_REDIS_NODES", " a:1, b:2 ,,c:3 ")
	t.Setenv("REDIRECTOR_RATE_LIMIT_RPS", "50.5")
	t.Setenv("REDIRECTOR_RATE_LIMIT_ENABLED", "false")
	t.Setenv("REDIRECTOR_POSITIVE_TTL", "90m")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(c.RedisNodes) != 3 || c.RedisNodes[1] != "b:2" {
		t.Fatalf("node list parsing: %v", c.RedisNodes)
	}
	if c.RateLimitRPS != 50.5 || c.RateLimitEnable {
		t.Fatalf("rate limit parsing: %+v", c)
	}
	if c.PositiveTTL != 90*time.Minute {
		t.Fatalf("duration parsing: %v", c.PositiveTTL)
	}
}

func TestValidation(t *testing.T) {
	t.Setenv("REDIRECTOR_REDIS_NODES", " , ")
	if _, err := Load(); err == nil {
		t.Fatal("expected error for empty node list")
	}
	t.Setenv("REDIRECTOR_REDIS_NODES", "a:1")
	t.Setenv("REDIRECTOR_RATE_LIMIT_BURST", "0")
	if _, err := Load(); err == nil {
		t.Fatal("expected error for zero burst")
	}
}
