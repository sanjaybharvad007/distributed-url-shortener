package com.urlshortener.api.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.urlshortener.api.click.ClickPublisher;
import com.urlshortener.api.config.AppProperties;
import com.urlshortener.api.config.WebConfig;
import com.urlshortener.api.domain.ShortUrl;
import com.urlshortener.api.ratelimit.RateLimiter;
import com.urlshortener.api.service.UrlShortenerService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Slice test for the HTTP layer: auth filter, rate-limit headers, validation, problem details. */
@WebMvcTest(controllers = {UrlController.class, RedirectController.class})
@Import({WebConfig.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(
    properties = {
      "app.base-url=http://sho.rt",
      "app.api-keys=test-key",
      "app.rate-limit.enabled=true",
      "app.rate-limit.requests-per-second=20",
      "app.rate-limit.burst=40",
      "app.redis.nodes=localhost:6379",
      "app.redis.virtual-nodes=150",
      "app.dynamodb.region=us-west-2",
      "app.dynamodb.table=short_urls",
      "app.id.worker-id=1",
      "app.id.epoch-millis=1704067200000",
      "app.click-consumer.group=g",
      "app.click-consumer.batch-size=1",
      "app.click-consumer.max-stream-length=1",
      "app.url.max-length=2048"
    })
class UrlControllerTest {

  private static final String KEY = "test-key";
  private static final Instant NOW = Instant.parse("2026-10-20T22:00:00Z");

  @Autowired MockMvc mvc;
  @MockBean UrlShortenerService service;
  @MockBean RateLimiter limiter;
  @MockBean ClickPublisher clicks;
  @MockBean Clock clock;

  @BeforeEach
  void allowAll() {
    when(limiter.check(anyString()))
        .thenReturn(new RateLimiter.Decision(true, 40, 39, 2, 0, false));
    when(clock.millis()).thenReturn(NOW.toEpochMilli());
  }

  @Test
  void rejectsMissingApiKey() throws Exception {
    mvc.perform(get("/api/v1/urls/abcd123"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void createsLinkAndReturns201WithLocation() throws Exception {
    when(service.create(
            anyString(), eq("https://example.com/x"), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new UrlShortenerService.CreateResult(link("abcd123"), true));

    mvc.perform(
            post("/api/v1/urls")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/x\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://sho.rt/abcd123"))
        .andExpect(header().string("RateLimit-Limit", "40"))
        .andExpect(header().string("RateLimit-Remaining", "39"))
        .andExpect(jsonPath("$.shortCode").value("abcd123"))
        .andExpect(jsonPath("$.shortUrl").value("http://sho.rt/abcd123"));
  }

  @Test
  void idempotentCreateReturns200() throws Exception {
    when(service.create(anyString(), anyString(), any(), any()))
        .thenReturn(new UrlShortenerService.CreateResult(link("abcd123"), false));

    mvc.perform(
            post("/api/v1/urls")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/x\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void validatesRequestBody() throws Exception {
    mvc.perform(
            post("/api/v1/urls")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"\",\"customAlias\":\"a!\",\"ttlSeconds\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail", containsString("longUrl")))
        .andExpect(jsonPath("$.detail", containsString("customAlias")))
        .andExpect(jsonPath("$.detail", containsString("ttlSeconds")));
  }

  @Test
  void aliasConflictIs409() throws Exception {
    when(service.create(anyString(), anyString(), any(), any()))
        .thenThrow(new UrlShortenerService.AliasTakenException("mine"));

    mvc.perform(
            post("/api/v1/urls")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/x\",\"customAlias\":\"mine\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type", containsString("alias-taken")));
  }

  @Test
  void unknownCodeIs404ProblemDetail() throws Exception {
    when(service.get("nope123")).thenThrow(new UrlShortenerService.NotFoundException("nope123"));

    mvc.perform(get("/api/v1/urls/nope123").header("X-API-Key", KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"));
  }

  @Test
  void rateLimitedRequestsGet429WithRetryAfter() throws Exception {
    when(limiter.check(anyString()))
        .thenReturn(new RateLimiter.Decision(false, 40, 0, 2, 1500, false));

    mvc.perform(get("/api/v1/urls/abcd123").header("X-API-Key", KEY))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(header().string("RateLimit-Remaining", "0"));
  }

  @Test
  void deleteReturns204() throws Exception {
    mvc.perform(delete("/api/v1/urls/abcd123").header("X-API-Key", KEY))
        .andExpect(status().isNoContent());
  }

  @Test
  void redirectIs302WithNoStoreCacheControl() throws Exception {
    when(service.resolve("abcd123")).thenReturn(Optional.of("https://example.com/x"));

    mvc.perform(get("/abcd123").header("User-Agent", "Mozilla/5.0"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/x"))
        .andExpect(header().string("Cache-Control", containsString("no-store")));
  }

  @Test
  void redirectOfUnknownCodeIs404() throws Exception {
    when(service.resolve("zzzz999")).thenReturn(Optional.empty());

    mvc.perform(get("/zzzz999")).andExpect(status().isNotFound());
  }

  @Test
  void redirectPathIsPublicAndNotRateLimitedByApiFilters() throws Exception {
    when(service.resolve("abcd123")).thenReturn(Optional.of("https://example.com/x"));
    when(limiter.check(anyString()))
        .thenReturn(new RateLimiter.Decision(false, 40, 0, 2, 1500, false));

    mvc.perform(get("/abcd123")).andExpect(status().isFound());
  }

  private static ShortUrl link(String code) {
    return new ShortUrl(
        code, "https://example.com/x", "h", "k_x", NOW, null, 0, ShortUrl.Status.ACTIVE, 0);
  }
}
