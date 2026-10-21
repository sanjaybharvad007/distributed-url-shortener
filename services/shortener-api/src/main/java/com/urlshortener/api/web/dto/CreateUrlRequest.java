package com.urlshortener.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUrlRequest(
    @Schema(example = "https://aws.amazon.com/dynamodb/", description = "Target URL (http/https)")
        @NotBlank
        @Size(max = 2048)
        String longUrl,
    @Schema(example = "my-launch", description = "Optional custom alias, 4-32 chars [0-9A-Za-z]")
        @Pattern(regexp = "^[0-9A-Za-z]{4,32}$", message = "must be 4-32 characters of [0-9A-Za-z]")
        String customAlias,
    @Schema(
            example = "86400",
            description = "Optional time-to-live in seconds (1 minute - 10 years)")
        @Min(60)
        @Max(315_360_000L)
        Long ttlSeconds) {}
