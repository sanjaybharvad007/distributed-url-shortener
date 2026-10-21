package com.urlshortener.api.ratelimit;

/** Distributed rate limiter. Implementations must be safe to call from many threads. */
public interface RateLimiter {

  /**
   * Outcome of a rate-limit check.
   *
   * @param allowed whether the request may proceed
   * @param limit configured burst capacity
   * @param remaining tokens left after this decision
   * @param resetSeconds seconds until the bucket is full again
   * @param retryAfterMillis how long the client should wait if {@code !allowed}
   * @param failedOpen true if the backing store was unavailable and the request was allowed anyway
   */
  record Decision(
      boolean allowed,
      long limit,
      long remaining,
      long resetSeconds,
      long retryAfterMillis,
      boolean failedOpen) {}

  Decision check(String clientKey);
}
