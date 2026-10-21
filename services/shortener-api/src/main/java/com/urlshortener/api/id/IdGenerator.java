package com.urlshortener.api.id;

/** Produces unique, non-negative 64-bit identifiers. */
public interface IdGenerator {
  long nextId();
}
