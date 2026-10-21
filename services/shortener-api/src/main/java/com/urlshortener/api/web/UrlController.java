package com.urlshortener.api.web;

import com.urlshortener.api.config.AppProperties;
import com.urlshortener.api.domain.Page;
import com.urlshortener.api.domain.ShortUrl;
import com.urlshortener.api.service.UrlShortenerService;
import com.urlshortener.api.web.dto.CreateUrlRequest;
import com.urlshortener.api.web.dto.PageResponse;
import com.urlshortener.api.web.dto.UrlResponse;
import com.urlshortener.api.web.dto.UrlStatsResponse;
import com.urlshortener.api.web.filter.ApiKeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/urls")
@Tag(name = "Links", description = "Create and manage short links")
public class UrlController {

  private final UrlShortenerService service;
  private final String baseUrl;

  public UrlController(UrlShortenerService service, AppProperties props) {
    this.service = service;
    this.baseUrl = props.baseUrl();
  }

  @PostMapping
  @Operation(summary = "Create a short link (idempotent per owner + URL)")
  @ApiResponse(responseCode = "201", description = "Created")
  @ApiResponse(responseCode = "200", description = "Existing link returned")
  @ApiResponse(responseCode = "400", description = "Invalid URL or alias")
  @ApiResponse(responseCode = "409", description = "Custom alias already taken")
  @ApiResponse(responseCode = "429", description = "Rate limited")
  public ResponseEntity<UrlResponse> create(
      @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTR) String owner,
      @Valid @RequestBody CreateUrlRequest req) {
    UrlShortenerService.CreateResult result =
        service.create(
            owner,
            req.longUrl(),
            Optional.ofNullable(req.customAlias()),
            Optional.ofNullable(req.ttlSeconds()).map(Duration::ofSeconds));
    UrlResponse body = UrlResponse.from(result.url(), baseUrl);
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
  }

  @GetMapping("/{code}")
  @Operation(summary = "Get link metadata")
  public UrlResponse get(@PathVariable String code) {
    return UrlResponse.from(service.get(code), baseUrl);
  }

  @GetMapping("/{code}/stats")
  @Operation(summary = "Get click statistics (eventually consistent)")
  public UrlStatsResponse stats(@PathVariable String code) {
    return UrlStatsResponse.from(service.get(code));
  }

  @GetMapping
  @Operation(summary = "List the caller's links, newest first")
  public PageResponse<UrlResponse> list(
      @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTR) String owner,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(required = false) String cursor) {
    Page<ShortUrl> page = service.list(owner, limit, cursor);
    return new PageResponse<>(
        page.items().stream().map(u -> UrlResponse.from(u, baseUrl)).toList(), page.nextCursor());
  }

  @DeleteMapping("/{code}")
  @Operation(summary = "Delete a link (tombstone + cache invalidation)")
  @ApiResponse(responseCode = "204", description = "Deleted")
  @ApiResponse(responseCode = "404", description = "Not found or not owned by caller")
  public ResponseEntity<Void> delete(
      @RequestAttribute(ApiKeyAuthFilter.OWNER_ATTR) String owner, @PathVariable String code) {
    service.delete(owner, code);
    return ResponseEntity.noContent().build();
  }
}
