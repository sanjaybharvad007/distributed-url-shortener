// Command redirector serves GET /{code} -> 302 for the distributed URL shortener.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"

	"github.com/urlshortener/redirector/internal/cache"
	"github.com/urlshortener/redirector/internal/clicks"
	"github.com/urlshortener/redirector/internal/config"
	"github.com/urlshortener/redirector/internal/metrics"
	"github.com/urlshortener/redirector/internal/ratelimit"
	"github.com/urlshortener/redirector/internal/server"
	"github.com/urlshortener/redirector/internal/store"
)

// version is injected at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	healthcheck := flag.Bool("healthcheck", false, "probe the local /healthz endpoint and exit (for container HEALTHCHECK)")
	flag.Parse()

	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(2)
	}
	if cfg.ServiceVersion == "" {
		cfg.ServiceVersion = version
	}
	if *healthcheck {
		os.Exit(probe(cfg.Addr))
	}

	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: logLevel()})).
		With("service", "redirector", "version", cfg.ServiceVersion)
	slog.SetDefault(log)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	shutdownTracing := setupTracing(ctx, cfg, log)
	defer shutdownTracing()

	m := metrics.New(nil)

	ring, err := cache.NewRing(cfg.RedisNodes, cfg.VirtualNodes, cfg.RedisTimeout, m)
	if err != nil {
		log.Error("redis ring", "err", err)
		os.Exit(1)
	}
	defer func() { _ = ring.Close() }()
	log.Info("redis ring ready", "nodes", cfg.RedisNodes, "virtualNodes", cfg.VirtualNodes)

	st, err := store.New(ctx, cfg.DynamoEndpoint, cfg.DynamoTable, cfg.DynamoTimeout, m)
	if err != nil {
		log.Error("dynamodb", "err", err)
		os.Exit(1)
	}

	var limiter *ratelimit.Limiter
	if cfg.RateLimitEnable {
		limiter = ratelimit.New(ring, cfg.RateLimitRPS, cfg.RateLimitBurst, m)
	}
	publisher := clicks.New(ring, cfg.ClickStream, cfg.ClickMaxLen, cfg.ClickBuffer, m, log)

	srv := server.New(server.Options{
		Cache:      cache.NewURLCache(ring, cfg.PositiveTTL, cfg.NegativeTTL, m),
		Ring:       ring,
		Store:      st,
		Limiter:    limiter,
		Clicks:     publisher,
		Metrics:    m,
		Log:        log,
		TrustProxy: cfg.TrustProxy,
		Version:    cfg.ServiceVersion,
	})

	httpServer := &http.Server{
		Addr:              cfg.Addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	go func() {
		log.Info("listening", "addr", cfg.Addr)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("http server", "err", err)
			stop()
		}
	}()

	<-ctx.Done()
	log.Info("shutting down", "timeout", cfg.ShutdownTimeout)
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		log.Warn("http shutdown", "err", err)
	}
	publisher.Close()
	log.Info("bye")
}

func setupTracing(ctx context.Context, cfg config.Config, log *slog.Logger) func() {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	if cfg.OTLPEndpoint == "" {
		return func() {}
	}
	exp, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(cfg.OTLPEndpoint+"/v1/traces"))
	if err != nil {
		log.Warn("otlp exporter disabled", "err", err)
		return func() {}
	}
	res, _ := resource.Merge(resource.Default(), resource.NewWithAttributes(
		semconv.SchemaURL,
		semconv.ServiceName("redirector"),
		semconv.ServiceVersion(cfg.ServiceVersion),
	))
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(cfg.TraceSampling))),
	)
	otel.SetTracerProvider(tp)
	log.Info("tracing enabled", "endpoint", cfg.OTLPEndpoint, "sampling", cfg.TraceSampling)
	return func() {
		c, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = tp.Shutdown(c)
	}
}

func logLevel() slog.Level {
	switch os.Getenv("LOG_LEVEL") {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

// probe is used by the container HEALTHCHECK (distroless has no curl/wget).
func probe(addr string) int {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return 1
	}
	if host == "" {
		host = "127.0.0.1"
	}
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://" + net.JoinHostPort(host, port) + "/healthz")
	if err != nil {
		return 1
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
