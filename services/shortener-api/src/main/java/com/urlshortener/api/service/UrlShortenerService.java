package com.urlshortener.api.service;

import com.urlshortener.api.cache.RedisKeys;
import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.cache.UrlCache;
import com.urlshortener.api.domain.Page;
import com.urlshortener.api.domain.ShortUrl;
import com.urlshortener.api.id.Base62;
import com.urlshortener.api.id.IdGenerator;
import com.urlshortener.api.repository.ShortUrlRepository;
import com.urlshortener.api.repository.ShortUrlRepository.CodeAlreadyExistsException;
import com.urlshortener.api.validation.UrlValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Link lifecycle: create (idempotent), resolve, list, delete. */
@Service
public class UrlShortenerService {

  private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);
  private static final int MAX_GENERATE_ATTEMPTS = 3;

  public static class NotFoundException extends RuntimeException {
    public NotFoundException(String code) {
      super("short link not found: " + code);
    }
  }

  public static class AliasTakenException extends RuntimeException {
    public AliasTakenException(String alias) {
      super("custom alias already in use: " + alias);
    }
  }

  /** Result of a create call; {@code created} is false when an existing link was reused. */
  public record CreateResult(ShortUrl url, boolean created) {}

  private final ShortUrlRepository repository;
  private final UrlCache cache;
  private final RedisRing ring;
  private final IdGenerator ids;
  private final UrlValidator validator;
  private final Clock clock;
  private final Counter createdCounter;
  private final Counter reusedCounter;
  private final Counter deletedCounter;

  public UrlShortenerService(
      ShortUrlRepository repository,
      UrlCache cache,
      RedisRing ring,
      IdGenerator ids,
      UrlValidator validator,
      Clock clock,
      MeterRegistry registry) {
    this.repository = repository;
    this.cache = cache;
    this.ring = ring;
    this.ids = ids;
    this.validator = validator;
    this.clock = clock;
    this.createdCounter =
        Counter.builder("links_total").tag("action", "created").register(registry);
    this.reusedCounter = Counter.builder("links_total").tag("action", "reused").register(registry);
    this.deletedCounter =
        Counter.builder("links_total").tag("action", "deleted").register(registry);
  }

  public CreateResult create(
      String ownerId, String rawUrl, Optional<String> customAlias, Optional<Duration> ttl) {
    String longUrl = validator.validateAndNormalize(rawUrl);
    String urlHash = urlHash(ownerId, longUrl);
    Instant now = clock.instant();
    Instant expiresAt = ttl.map(now::plus).orElse(null);

    // Idempotent create: same owner + same URL (without a custom alias) returns the existing link.
    if (customAlias.isEmpty()) {
      Optional<ShortUrl> existing = repository.findByUrlHash(urlHash);
      if (existing.isPresent()) {
        reusedCounter.increment();
        return new CreateResult(existing.get(), false);
      }
    }

    if (customAlias.isPresent()) {
      String alias = customAlias.get();
      if (!Base62.isValidCode(alias)) {
        throw new UrlValidator.InvalidUrlException(
            "customAlias must be 4-32 characters of [0-9A-Za-z]");
      }
      ShortUrl url = newLink(alias, longUrl, urlHash, ownerId, now, expiresAt);
      try {
        repository.insert(url);
      } catch (CodeAlreadyExistsException e) {
        throw new AliasTakenException(alias);
      }
      cache.put(alias, longUrl, expiresAt);
      createdCounter.increment();
      return new CreateResult(url, true);
    }

    // Snowflake IDs are unique by construction; the retry loop only guards against a custom alias
    // that happens to collide with a generated code.
    for (int attempt = 1; attempt <= MAX_GENERATE_ATTEMPTS; attempt++) {
      String code = Base62.encode(ids.nextId());
      ShortUrl url = newLink(code, longUrl, urlHash, ownerId, now, expiresAt);
      try {
        repository.insert(url);
        cache.put(code, longUrl, expiresAt);
        createdCounter.increment();
        return new CreateResult(url, true);
      } catch (CodeAlreadyExistsException e) {
        log.warn("generated code {} collided (attempt {})", code, attempt);
      }
    }
    throw new IllegalStateException("could not allocate a unique short code");
  }

  public ShortUrl get(String code) {
    return repository
        .findByCode(code)
        .filter(ShortUrl::isActive)
        .orElseThrow(() -> new NotFoundException(code));
  }

  /** Resolve a code to its target for a redirect; cache-first, DynamoDB on miss. */
  public Optional<String> resolve(String code) {
    if (!Base62.isValidCode(code)) {
      return Optional.empty();
    }
    UrlCache.Lookup lookup = cache.get(code);
    if (lookup.url().isPresent()) {
      return lookup.url();
    }
    if (lookup.knownMissing()) {
      return Optional.empty();
    }
    Optional<ShortUrl> stored = repository.findByCode(code).filter(ShortUrl::isActive);
    if (stored.isPresent()) {
      cache.put(code, stored.get().longUrl(), stored.get().expiresAt());
      return Optional.of(stored.get().longUrl());
    }
    cache.putNegative(code);
    return Optional.empty();
  }

  public Page<ShortUrl> list(String ownerId, int limit, String cursor) {
    return repository.findByOwner(ownerId, limit, cursor);
  }

  public void delete(String ownerId, String code) {
    if (!repository.softDelete(code, ownerId)) {
      throw new NotFoundException(code);
    }
    cache.evict(code);
    deletedCounter.increment();
  }

  private ShortUrl newLink(
      String code, String longUrl, String urlHash, String ownerId, Instant now, Instant expiresAt) {
    int shard = ring.shardFor(RedisKeys.url(code));
    return new ShortUrl(
        code, longUrl, urlHash, ownerId, now, expiresAt, 0L, ShortUrl.Status.ACTIVE, shard);
  }

  static String urlHash(String ownerId, String longUrl) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(ownerId.getBytes(StandardCharsets.UTF_8));
      md.update((byte) '\n');
      md.update(longUrl.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
