// Package server exposes the redirect endpoint, health probes and metrics.
package server

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/trace"

	"github.com/urlshortener/redirector/internal/cache"
	"github.com/urlshortener/redirector/internal/clicks"
	"github.com/urlshortener/redirector/internal/metrics"
	"github.com/urlshortener/redirector/internal/ratelimit"
	"github.com/urlshortener/redirector/internal/store"
)

var codeRe = regexp.MustCompile(`^[0-9A-Za-z]{4,32}$`)

// Options configures a Server.
type Options struct {
	Cache      *cache.URLCache
	Ring       *cache.Ring
	Store      store.Store
	Limiter    *ratelimit.Limiter // nil disables rate limiting
	Clicks     *clicks.Publisher  // nil disables click events
	Metrics    *metrics.Metrics
	Log        *slog.Logger
	TrustProxy bool
	Version    string
}

// Server is the HTTP front end.
type Server struct {
	opts Options
	mux  *http.ServeMux
}

// New wires the routes.
func New(o Options) *Server {
	s := &Server{opts: o, mux: http.NewServeMux()}
	s.mux.Handle("GET /metrics", promhttp.Handler())
	s.mux.HandleFunc("GET /healthz", s.healthz)
	s.mux.HandleFunc("GET /readyz", s.readyz)
	s.mux.HandleFunc("GET /{code}", s.redirect)
	s.mux.HandleFunc("GET /{$}", s.root)
	return s
}

// Handler returns the fully instrumented handler.
func (s *Server) Handler() http.Handler {
	var h http.Handler = s.mux
	h = s.instrument(h)
	h = otelhttp.NewHandler(h, "redirector", otelhttp.WithSpanNameFormatter(func(_ string, r *http.Request) string {
		if p := r.Pattern; p != "" {
			return r.Method + " " + p
		}
		return r.Method + " " + r.URL.Path
	}))
	return h
}

// ---------------------------------------------------------------- handlers

func (s *Server) redirect(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !codeRe.MatchString(code) {
		http.NotFound(w, r)
		return
	}
	ctx := r.Context()

	if s.opts.Limiter != nil {
		d := s.opts.Limiter.Check(ctx, "ip:"+clientIP(r, s.opts.TrustProxy))
		w.Header().Set("RateLimit-Limit", strconv.FormatInt(d.Limit, 10))
		w.Header().Set("RateLimit-Remaining", strconv.FormatInt(d.Remaining, 10))
		w.Header().Set("RateLimit-Reset", strconv.FormatInt(d.ResetSecs, 10))
		if !d.Allowed {
			secs := (d.RetryAfter + 999) / 1000
			if secs < 1 {
				secs = 1
			}
			w.Header().Set("Retry-After", strconv.FormatInt(secs, 10))
			problem(w, http.StatusTooManyRequests, "rate limit exceeded")
			return
		}
	}

	target, res := s.opts.Cache.Get(ctx, code)
	switch res {
	case cache.Hit:
		// fast path
	case cache.Negative:
		http.NotFound(w, r)
		return
	default: // Miss or Error: consult DynamoDB
		link, err := s.opts.Store.Get(ctx, code)
		switch {
		case errors.Is(err, store.ErrNotFound):
			if res == cache.Miss {
				s.opts.Cache.SetNegative(ctx, code)
			}
			http.NotFound(w, r)
			return
		case err != nil:
			s.opts.Log.ErrorContext(ctx, "store lookup failed", "code", code, "err", err)
			w.Header().Set("Retry-After", "2")
			problem(w, http.StatusServiceUnavailable, "link store temporarily unavailable")
			return
		}
		target = link.LongURL
		if res == cache.Miss {
			s.opts.Cache.Set(ctx, code, target, link.ExpiresAt)
		}
	}

	if s.opts.Clicks != nil {
		s.opts.Clicks.Publish(clicks.Event{
			Code:         code,
			Timestamp:    time.Now(),
			UAClass:      classifyUA(r.UserAgent()),
			ReferrerHost: referrerHost(r.Referer()),
		})
	}

	w.Header().Set("Location", target)
	w.Header().Set("Cache-Control", "private, max-age=0, no-store")
	w.Header().Set("Referrer-Policy", "no-referrer-when-downgrade")
	w.WriteHeader(http.StatusFound)
}

func (s *Server) root(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"service":"redirector","version":"` + s.opts.Version + `"}`))
}

// healthz is liveness: the process is up.
func (s *Server) healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// readyz is readiness: at least one Redis shard and DynamoDB are reachable.
// Partial shard outages are reported in the body but do not fail readiness,
// because cache misses degrade gracefully to DynamoDB.
func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	up, redisErr := s.opts.Ring.Ping(ctx)
	dynamoErr := s.opts.Store.Ping(ctx)

	status := http.StatusOK
	if up == 0 || dynamoErr != nil {
		status = http.StatusServiceUnavailable
	}
	body := `{"status":"` + map[bool]string{true: "ok", false: "degraded"}[status == http.StatusOK] + `",` +
		`"redisShardsUp":"` + strconv.Itoa(up) + "/" + strconv.Itoa(s.opts.Ring.Size()) + `",` +
		`"redis":"` + errString(redisErr) + `",` +
		`"dynamodb":"` + errString(dynamoErr) + `"}`
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

// ---------------------------------------------------------------- middleware

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (sw *statusWriter) WriteHeader(code int) {
	sw.status = code
	sw.ResponseWriter.WriteHeader(code)
}

func (s *Server) instrument(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		route := r.Pattern
		if route == "" {
			route = "unmatched"
		}
		s.opts.Metrics.RequestDuration.WithLabelValues(route, strconv.Itoa(sw.status)).
			Observe(time.Since(start).Seconds())

		if sw.status >= 500 || s.opts.Log.Enabled(r.Context(), slog.LevelDebug) {
			attrs := []any{
				"method", r.Method, "path", r.URL.Path, "status", sw.status,
				"duration_ms", float64(time.Since(start).Microseconds()) / 1000,
				"client_ip", clientIP(r, s.opts.TrustProxy),
			}
			if sc := trace.SpanContextFromContext(r.Context()); sc.HasTraceID() {
				attrs = append(attrs, "trace_id", sc.TraceID().String())
			}
			level := slog.LevelDebug
			if sw.status >= 500 {
				level = slog.LevelError
			}
			s.opts.Log.Log(r.Context(), level, "request", attrs...)
		}
	})
}

// ---------------------------------------------------------------- helpers

func problem(w http.ResponseWriter, status int, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"type":"about:blank","title":"` + http.StatusText(status) +
		`","status":` + strconv.Itoa(status) + `,"detail":"` + detail + `"}`))
}

func errString(err error) string {
	if err == nil {
		return "ok"
	}
	return strings.ReplaceAll(err.Error(), `"`, `'`)
}

// clientIP returns the caller's IP, honouring X-Forwarded-For only when the
// service sits behind a trusted proxy (the ALB).
func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			if i := strings.IndexByte(xff, ','); i > 0 {
				return strings.TrimSpace(xff[:i])
			}
			return strings.TrimSpace(xff)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func classifyUA(ua string) string {
	if ua == "" {
		return "unknown"
	}
	l := strings.ToLower(ua)
	switch {
	case strings.Contains(l, "bot"), strings.Contains(l, "crawler"), strings.Contains(l, "spider"), strings.Contains(l, "curl"):
		return "bot"
	case strings.Contains(l, "mobile"), strings.Contains(l, "android"), strings.Contains(l, "iphone"):
		return "mobile"
	default:
		return "desktop"
	}
}

func referrerHost(ref string) string {
	if ref == "" {
		return ""
	}
	u, err := url.Parse(ref)
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Hostname())
}
