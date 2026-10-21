package com.urlshortener.api.ratelimit;

import com.urlshortener.api.cache.RedisKeys;
import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.config.AppProperties;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token bucket evaluated atomically by a Lua script on the shard that owns the client's key. Fails
 * open (allows the request, counts the event) if that shard is unreachable; see ADR 0005.
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
  private static final String SCRIPT = loadScript("/lua/token_bucket.lua");

  private final RedisRing ring;
  private final double rate;
  private final long burst;
  private final Counter allowedCounter;
  private final Counter deniedCounter;
  private final Counter failOpenCounter;

  public RedisTokenBucketRateLimiter(RedisRing ring, AppProperties props, MeterRegistry registry) {
    this.ring = ring;
    this.rate = props.rateLimit().requestsPerSecond();
    this.burst = props.rateLimit().burst();
    this.allowedCounter = counter(registry, "allowed");
    this.deniedCounter = counter(registry, "denied");
    this.failOpenCounter = counter(registry, "failopen");
  }

  private static Counter counter(MeterRegistry registry, String result) {
    return Counter.builder("ratelimit_decisions_total")
        .description("Rate limit decisions by result")
        .tag("result", result)
        .register(registry);
  }

  @Override
  public Decision check(String clientKey) {
    String key = RedisKeys.rateLimit(clientKey);
    try {
      List<Long> r =
          ring.forKey(key)
              .eval(
                  SCRIPT,
                  ScriptOutputType.MULTI,
                  new String[] {key},
                  String.valueOf(burst),
                  String.valueOf(rate),
                  "1");
      boolean allowed = r.get(0) == 1L;
      (allowed ? allowedCounter : deniedCounter).increment();
      return new Decision(allowed, burst, r.get(1), r.get(3), r.get(2), false);
    } catch (RedisException e) {
      failOpenCounter.increment();
      log.warn("rate limiter failing open for {}: {}", clientKey, e.toString());
      return new Decision(true, burst, burst, 0, 0, true);
    }
  }

  private static String loadScript(String path) {
    try (InputStream in = RedisTokenBucketRateLimiter.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
