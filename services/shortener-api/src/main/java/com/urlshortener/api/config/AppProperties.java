package com.urlshortener.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Strongly-typed view of the {@code app.*} configuration block. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank String baseUrl,
    @NotEmpty List<String> apiKeys,
    @Valid @NotNull Redis redis,
    @Valid @NotNull DynamoDb dynamodb,
    @Valid @NotNull Id id,
    @Valid @NotNull RateLimit rateLimit,
    @Valid @NotNull ClickConsumer clickConsumer,
    @Valid @NotNull Url url) {

  public record Redis(
      @NotEmpty List<String> nodes,
      @Min(1) int virtualNodes,
      Duration commandTimeout,
      Duration positiveTtl,
      Duration negativeTtl) {}

  public record DynamoDb(
      String endpoint,
      @NotBlank String region,
      @NotBlank String table,
      Duration callTimeout,
      Duration attemptTimeout) {}

  public record Id(long workerId, long epochMillis) {}

  public record RateLimit(
      boolean enabled, @Positive double requestsPerSecond, @Min(1) long burst) {}

  public record ClickConsumer(
      boolean enabled,
      @NotBlank String group,
      @Min(1) int batchSize,
      @Min(1) long maxStreamLength) {}

  public record Url(@Min(16) int maxLength, List<String> blockedHosts) {}
}
