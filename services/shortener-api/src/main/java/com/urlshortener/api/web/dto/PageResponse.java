package com.urlshortener.api.web.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, String nextCursor) {}
