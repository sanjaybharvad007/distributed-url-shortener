package com.urlshortener.api.web.dto;

import com.urlshortener.api.domain.ShortUrl;
import java.time.Instant;

public record UrlResponse(
    String shortCode, String shortUrl, String longUrl, Instant createdAt, Instant expiresAt) {

  public static UrlResponse from(ShortUrl u, String baseUrl) {
    return new UrlResponse(
        u.shortCode(), baseUrl + "/" + u.shortCode(), u.longUrl(), u.createdAt(), u.expiresAt());
  }
}
