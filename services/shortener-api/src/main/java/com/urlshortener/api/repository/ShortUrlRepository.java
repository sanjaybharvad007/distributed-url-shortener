package com.urlshortener.api.repository;

import com.urlshortener.api.domain.Page;
import com.urlshortener.api.domain.ShortUrl;
import java.util.Map;
import java.util.Optional;

/** Persistence port for short links. */
public interface ShortUrlRepository {

  /** Thrown when a short code is already taken. */
  class CodeAlreadyExistsException extends RuntimeException {
    public CodeAlreadyExistsException(String code) {
      super("short code already exists: " + code);
    }
  }

  /** Inserts the link; fails with {@link CodeAlreadyExistsException} if the code is taken. */
  void insert(ShortUrl url);

  Optional<ShortUrl> findByCode(String code);

  /** Eventually-consistent lookup by {@code urlHash} through the GSI. */
  Optional<ShortUrl> findByUrlHash(String urlHash);

  Page<ShortUrl> findByOwner(String ownerId, int limit, String cursor);

  /** Marks the link deleted. Returns false if it did not exist or was already deleted. */
  boolean softDelete(String code, String ownerId);

  /** Atomically adds {@code delta} clicks to each code. */
  void addClicks(Map<String, Long> deltasByCode);
}
