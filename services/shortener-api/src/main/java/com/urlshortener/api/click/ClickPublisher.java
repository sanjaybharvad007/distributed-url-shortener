package com.urlshortener.api.click;

import com.urlshortener.api.cache.RedisKeys;
import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.config.AppProperties;
import io.lettuce.core.RedisException;
import io.lettuce.core.XAddArgs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes click events to the Redis Stream off the request thread. Used by the Java fallback
 * redirect endpoint; the Go redirector publishes identically formatted events.
 */
@Component
public class ClickPublisher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ClickPublisher.class);

  private final RedisRing ring;
  private final long maxLen;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Counter published;
  private final Counter dropped;

  public ClickPublisher(RedisRing ring, AppProperties props, MeterRegistry registry) {
    this.ring = ring;
    this.maxLen = props.clickConsumer().maxStreamLength();
    this.published =
        Counter.builder("click_events_published_total").tag("source", "api").register(registry);
    this.dropped =
        Counter.builder("click_events_dropped_total").tag("source", "api").register(registry);
  }

  public void publish(ClickEvent event) {
    executor.submit(
        () -> {
          try {
            ring.forKey(RedisKeys.CLICK_STREAM)
                .xadd(
                    RedisKeys.CLICK_STREAM,
                    XAddArgs.Builder.maxlen(maxLen).approximateTrimming(),
                    event.toFields());
            published.increment();
          } catch (RedisException e) {
            dropped.increment();
            log.warn("dropping click event for {}: {}", event.code(), e.toString());
          }
        });
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
