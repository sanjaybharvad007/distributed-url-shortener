package com.urlshortener.api.domain;

import java.time.Instant;

/** A persisted short link. Mirrors one item in the {@code short_urls} DynamoDB table. */
public record ShortUrl(
    String shortCode,
    String longUrl,
    String urlHash,
    String ownerId,
    Instant createdAt,
    Instant expiresAt, // null = never
    long clickCount,
    Status status,
    int shardId) {

  public enum Status {
    ACTIVE,
    DELETED
  }

  public boolean isActive() {
    return status == Status.ACTIVE && !isExpired();
  }

  public boolean isExpired() {
    return expiresAt != null && !expiresAt.isAfter(Instant.now());
  }

  public ShortUrl withClickCount(long count) {
    return new ShortUrl(
        shortCode, longUrl, urlHash, ownerId, createdAt, expiresAt, count, status, shardId);
  }
}
