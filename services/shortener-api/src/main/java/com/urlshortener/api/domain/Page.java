package com.urlshortener.api.domain;

import java.util.List;

/** A page of results with an opaque cursor for the next page ({@code null} when exhausted). */
public record Page<T>(List<T> items, String nextCursor) {}
