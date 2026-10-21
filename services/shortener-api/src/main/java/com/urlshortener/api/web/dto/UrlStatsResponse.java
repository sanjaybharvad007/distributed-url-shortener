package com.urlshortener.api.web.dto;

import com.urlshortener.api.domain.ShortUrl;
import java.time.Instant;

public record UrlStatsResponse(
    String shortCode,
    String longUrl,
    long clickCount,
    Instant createdAt,
    Instant expiresAt,
    int shardId) {

  public static UrlStatsResponse from(ShortUrl u) {
    return new UrlStatsResponse(
        u.shortCode(), u.longUrl(), u.clickCount(), u.createdAt(), u.expiresAt(), u.shardId());
  }
}
