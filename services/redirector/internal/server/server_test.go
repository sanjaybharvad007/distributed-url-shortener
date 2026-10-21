package server

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/urlshortener/redirector/internal/cache"
	"github.com/urlshortener/redirector/internal/clicks"
	"github.com/urlshortener/redirector/internal/metrics"
	"github.com/urlshortener/redirector/internal/ratelimit"
	"github.com/urlshortener/redirector/internal/store"
)

// fakeStore is an in-memory store.Store that counts calls.
type fakeStore struct {
	mu    sync.Mutex
	links map[string]store.Link
	calls int
	err   error
}

func (f *fakeStore) Get(_ context.Context, code string) (store.Link, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.err != nil {
		return store.Link{}, f.err
	}
	l, ok := f.links[code]
	if !ok {
		return store.Link{}, store.ErrNotFound
	}
	return l, nil
}

func (f *fakeStore) Ping(context.Context) error { return f.err }

func (f *fakeStore) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

type fixture struct {
	shards []*miniredis.Miniredis
	ring   *cache.Ring
	store  *fakeStore
	srv    *httptest.Server
	pub    *clicks.Publisher
}

func newFixture(t *testing.T, rps float64, burst int64) *fixture {
	t.Helper()
	m := metrics.New(prometheus.NewRegistry())
	var nodes []string
	var shards []*miniredis.Miniredis
	for i := 0; i < 3; i++ {
		s := miniredis.RunT(t)
		shards = append(shards, s)
		nodes = append(nodes, s.Addr())
	}
	ring, err := cache.NewRing(nodes, 150, 200*time.Millisecond, m)
	if err != nil {
		t.Fatal(err)
	}
	fs := &fakeStore{links: map[string]store.Link{
		"abcd123": {Code: "abcd123", LongURL: "https://example.com/a"},
		"ttl1234": {Code: "ttl1234", LongURL: "https://example.com/t", ExpiresAt: time.Now().Add(time.Hour)},
	}}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	pub := clicks.New(ring, cache.ClickStream, 1000, 100, m, log)
	var limiter *ratelimit.Limiter
	if rps > 0 {
		limiter = ratelimit.New(ring, rps, burst, m)
	}
	s := New(Options{
		Cache:      cache.NewURLCache(ring, time.Hour, time.Minute, m),
		Ring:       ring,
		Store:      fs,
		Limiter:    limiter,
		Clicks:     pub,
		Metrics:    m,
		Log:        log,
		TrustProxy: true,
		Version:    "test",
	})
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(func() {
		ts.Close()
		pub.Close()
		_ = ring.Close()
	})
	return &fixture{shards: shards, ring: ring, store: fs, srv: ts, pub: pub}
}

func noRedirect() *http.Client {
	return &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func get(t *testing.T, c *http.Client, url string, headers map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp
}

func TestRedirectMissThenHit(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()

	resp := get(t, c, f.srv.URL+"/abcd123", nil)
	if resp.StatusCode != http.StatusFound || resp.Header.Get("Location") != "https://example.com/a" {
		t.Fatalf("miss: got %d %q", resp.StatusCode, resp.Header.Get("Location"))
	}
	if cc := resp.Header.Get("Cache-Control"); cc == "" || cc == "public" {
		t.Fatalf("expected private cache-control, got %q", cc)
	}
	if f.store.callCount() != 1 {
		t.Fatalf("expected 1 store call, got %d", f.store.callCount())
	}

	// The populate landed on exactly the owning shard.
	key := cache.URLKey("abcd123")
	owner := f.ring.Shard(key)
	for i, s := range f.shards {
		v, err := s.Get(key)
		if i == owner {
			if err != nil || v != "https://example.com/a" {
				t.Fatalf("owner shard %d should hold the key, got %q %v", i, v, err)
			}
		} else if err == nil {
			t.Fatalf("non-owner shard %d unexpectedly holds the key", i)
		}
	}

	resp = get(t, c, f.srv.URL+"/abcd123", nil)
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("hit: got %d", resp.StatusCode)
	}
	if f.store.callCount() != 1 {
		t.Fatalf("cache hit should not call the store, calls=%d", f.store.callCount())
	}
}

func TestPositiveTTLIsCappedByLinkExpiry(t *testing.T) {
	f := newFixture(t, 0, 0)
	get(t, noRedirect(), f.srv.URL+"/ttl1234", nil)
	key := cache.URLKey("ttl1234")
	ttl := f.shards[f.ring.Shard(key)].TTL(key)
	if ttl <= 0 || ttl > time.Hour {
		t.Fatalf("expected ttl within (0, 1h], got %v", ttl)
	}
}

func TestUnknownCodeIsNegativelyCached(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	for i := 0; i < 3; i++ {
		if resp := get(t, c, f.srv.URL+"/nope9999", nil); resp.StatusCode != http.StatusNotFound {
			t.Fatalf("got %d", resp.StatusCode)
		}
	}
	if f.store.callCount() != 1 {
		t.Fatalf("negative cache should absorb repeats, store calls=%d", f.store.callCount())
	}
	key := cache.URLKey("nope9999")
	if v, _ := f.shards[f.ring.Shard(key)].Get(key); v != cache.NegativeSentinel {
		t.Fatalf("expected negative sentinel, got %q", v)
	}
}

func TestMalformedCodesAre404WithoutIO(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	for _, p := range []string{"/ab", "/has-dash", "/" + strings.Repeat("a", 40)} {
		if resp := get(t, c, f.srv.URL+p, nil); resp.StatusCode != http.StatusNotFound {
			t.Errorf("%q: got %d", p, resp.StatusCode)
		}
	}
	if f.store.callCount() != 0 {
		t.Fatalf("malformed codes must not hit the store")
	}
}

func TestStoreOutageWithWarmCacheStillRedirects(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	get(t, c, f.srv.URL+"/abcd123", nil) // warm
	f.store.mu.Lock()
	f.store.err = errors.New("dynamodb down")
	f.store.mu.Unlock()

	if resp := get(t, c, f.srv.URL+"/abcd123", nil); resp.StatusCode != http.StatusFound {
		t.Fatalf("warm key should still redirect, got %d", resp.StatusCode)
	}
	resp := get(t, c, f.srv.URL+"/cold5678", nil)
	if resp.StatusCode != http.StatusServiceUnavailable || resp.Header.Get("Retry-After") == "" {
		t.Fatalf("cold key during outage should be 503 with Retry-After, got %d", resp.StatusCode)
	}
}

func TestShardOutageFallsThroughToStore(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	key := cache.URLKey("abcd123")
	f.shards[f.ring.Shard(key)].Close() // kill the owning shard

	resp := get(t, c, f.srv.URL+"/abcd123", nil)
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("expected redirect via store despite shard outage, got %d", resp.StatusCode)
	}
}

func TestRateLimitPerClientIP(t *testing.T) {
	f := newFixture(t, 10, 5)
	c := noRedirect()
	var denied int
	for i := 0; i < 8; i++ {
		resp := get(t, c, f.srv.URL+"/abcd123", map[string]string{"X-Forwarded-For": "203.0.113.7"})
		if resp.StatusCode == http.StatusTooManyRequests {
			denied++
			if resp.Header.Get("Retry-After") == "" {
				t.Fatal("429 without Retry-After")
			}
		}
		if resp.Header.Get("RateLimit-Limit") != "5" {
			t.Fatalf("missing RateLimit-Limit header")
		}
	}
	if denied != 3 {
		t.Fatalf("burst 5 of 8 requests: expected 3 denied, got %d", denied)
	}
	// Another client has its own bucket.
	if resp := get(t, c, f.srv.URL+"/abcd123", map[string]string{"X-Forwarded-For": "198.51.100.9"}); resp.StatusCode != http.StatusFound {
		t.Fatalf("other client should be allowed, got %d", resp.StatusCode)
	}
}

func TestRateLimiterFailsOpenWhenShardDown(t *testing.T) {
	f := newFixture(t, 10, 1)
	c := noRedirect()
	ip := "203.0.113.42"
	rlKey := cache.RateLimitKey("ip:" + ip)
	f.shards[f.ring.Shard(rlKey)].Close()
	for i := 0; i < 5; i++ {
		resp := get(t, c, f.srv.URL+"/abcd123", map[string]string{"X-Forwarded-For": ip})
		if resp.StatusCode == http.StatusTooManyRequests {
			t.Fatal("limiter should fail open when its shard is down")
		}
	}
}

func TestClickEventsReachTheStream(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	for i := 0; i < 5; i++ {
		get(t, c, f.srv.URL+"/abcd123", map[string]string{"User-Agent": "Mozilla/5.0 (iPhone)", "Referer": "https://News.Example.org/x"})
	}
	owner := f.shards[f.ring.Shard(cache.ClickStream)]
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if msgs, _ := owner.Stream(cache.ClickStream); len(msgs) == 5 {
			got := map[string]string{}
			for i := 0; i < len(msgs[0].Values); i += 2 {
				got[msgs[0].Values[i]] = msgs[0].Values[i+1]
			}
			if got["code"] != "abcd123" || got["ua"] != "mobile" || got["ref"] != "news.example.org" {
				t.Fatalf("unexpected event fields %v", got)
			}
			if _, err := strconv.ParseInt(got["ts"], 10, 64); err != nil {
				t.Fatalf("bad ts %q", got["ts"])
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	msgs, _ := owner.Stream(cache.ClickStream)
	t.Fatalf("expected 5 click events, got %d", len(msgs))
}

func TestProbesAndMetrics(t *testing.T) {
	f := newFixture(t, 0, 0)
	c := noRedirect()
	if resp := get(t, c, f.srv.URL+"/healthz", nil); resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz %d", resp.StatusCode)
	}
	if resp := get(t, c, f.srv.URL+"/readyz", nil); resp.StatusCode != http.StatusOK {
		t.Fatalf("readyz %d", resp.StatusCode)
	}
	get(t, c, f.srv.URL+"/abcd123", nil)
	req, _ := http.NewRequest(http.MethodGet, f.srv.URL+"/metrics", nil)
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || len(body) == 0 {
		t.Fatalf("metrics endpoint %d", resp.StatusCode)
	}

	// Readiness degrades when every shard is gone.
	for _, s := range f.shards {
		s.Close()
	}
	if resp := get(t, c, f.srv.URL+"/readyz", nil); resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("readyz with all shards down should be 503, got %d", resp.StatusCode)
	}
}

func TestClientIPHonoursProxyOnlyWhenTrusted(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/x", nil)
	r.RemoteAddr = "10.0.0.1:1234"
	r.Header.Set("X-Forwarded-For", "203.0.113.5, 10.0.0.9")
	if ip := clientIP(r, true); ip != "203.0.113.5" {
		t.Errorf("trusted: %q", ip)
	}
	if ip := clientIP(r, false); ip != "10.0.0.1" {
		t.Errorf("untrusted: %q", ip)
	}
}
