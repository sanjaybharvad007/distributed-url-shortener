package com.urlshortener.api.cache;

/**
 * Canonical Redis key layout. Mirrored in the Go redirector ({@code internal/cache/keys.go});
 * changing anything here requires changing it there as well.
 */
public final class RedisKeys {

  private RedisKeys() {}

  /** Sentinel value for a negative cache entry (code known not to exist). */
  public static final String NEGATIVE = "!";

  public static String url(String code) {
    return "url:" + code;
  }

  public static String rateLimit(String client) {
    return "rl:" + client;
  }

  /** The click-event stream. Routed through the ring like any other key. */
  public static final String CLICK_STREAM = "clicks";
}
