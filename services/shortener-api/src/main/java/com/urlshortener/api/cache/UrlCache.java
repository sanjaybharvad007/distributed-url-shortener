package com.urlshortener.api.cache;

import com.urlshortener.api.config.AppProperties;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sharded look-aside cache for {@code code -> long URL}. All failures are swallowed and counted:
 * the cache is an optimisation, never a dependency.
 */
@Component
public class UrlCache {

  private static final Logger log = LoggerFactory.getLogger(UrlCache.class);

  private final RedisRing ring;
  private final Duration positiveTtl;
  private final Duration negativeTtl;
  private final Counter hits;
  private final Counter misses;
  private final Counter negatives;
  private final Counter errors;

  public UrlCache(RedisRing ring, AppProperties props, MeterRegistry registry) {
    this.ring = ring;
    this.positiveTtl = props.redis().positiveTtl();
    this.negativeTtl = props.redis().negativeTtl();
    this.hits = counter(registry, "hit");
    this.misses = counter(registry, "miss");
    this.negatives = counter(registry, "negative");
    this.errors = counter(registry, "error");
  }

  private static Counter counter(MeterRegistry registry, String result) {
    return Counter.builder("cache_lookups_total")
        .description("URL cache lookups by result")
        .tag("result", result)
        .register(registry);
  }

  /** Lookup result: present → URL, negative → empty with {@code known=true}, miss → empty. */
  public record Lookup(Optional<String> url, boolean knownMissing) {
    static final Lookup MISS = new Lookup(Optional.empty(), false);
    static final Lookup NEGATIVE = new Lookup(Optional.empty(), true);
  }

  public Lookup get(String code) {
    String key = RedisKeys.url(code);
    try {
      String value = ring.forKey(key).get(key);
      if (value == null) {
        misses.increment();
        return Lookup.MISS;
      }
      if (RedisKeys.NEGATIVE.equals(value)) {
        negatives.increment();
        return Lookup.NEGATIVE;
      }
      hits.increment();
      return new Lookup(Optional.of(value), false);
    } catch (RedisException e) {
      errors.increment();
      log.warn("cache get failed for {} on shard {}: {}", code, ring.nodeFor(key), e.toString());
      return Lookup.MISS;
    }
  }

  /** Write-through populate. TTL is capped by the link's own expiry. */
  public void put(String code, String longUrl, Instant expiresAt) {
    String key = RedisKeys.url(code);
    try {
      ring.forKey(key).set(key, longUrl, SetArgs.Builder.px(ttlFor(expiresAt).toMillis()));
    } catch (RedisException e) {
      errors.increment();
      log.warn("cache put failed for {}: {}", code, e.toString());
    }
  }

  /** Mark a code as known-missing so repeated misses do not hammer DynamoDB. */
  public void putNegative(String code) {
    String key = RedisKeys.url(code);
    try {
      ring.forKey(key).set(key, RedisKeys.NEGATIVE, SetArgs.Builder.px(negativeTtl.toMillis()));
    } catch (RedisException e) {
      errors.increment();
      log.warn("negative cache put failed for {}: {}", code, e.toString());
    }
  }

  /** Invalidate on delete: replace with a negative entry rather than a bare DEL to beat races. */
  public void evict(String code) {
    putNegative(code);
  }

  Duration ttlFor(Instant expiresAt) {
    if (expiresAt == null) {
      return positiveTtl;
    }
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    if (remaining.isNegative() || remaining.isZero()) {
      return Duration.ofSeconds(1);
    }
    return remaining.compareTo(positiveTtl) < 0 ? remaining : positiveTtl;
  }
}
