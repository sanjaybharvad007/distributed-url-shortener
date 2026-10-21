package com.urlshortener.api.web;

import com.urlshortener.api.click.ClickEvent;
import com.urlshortener.api.click.ClickPublisher;
import com.urlshortener.api.service.UrlShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fallback redirect endpoint. In production the Go redirector serves this path; the Java service
 * keeps the system functional on its own for small deployments and local development.
 */
@RestController
@Tag(name = "Redirect")
public class RedirectController {

  private final UrlShortenerService service;
  private final ClickPublisher clicks;
  private final Clock clock;

  public RedirectController(UrlShortenerService service, ClickPublisher clicks, Clock clock) {
    this.service = service;
    this.clicks = clicks;
    this.clock = clock;
  }

  @GetMapping("/{code:[0-9A-Za-z]{4,32}}")
  @Operation(summary = "Redirect to the target URL")
  public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
    Optional<String> target = service.resolve(code);
    if (target.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    clicks.publish(
        new ClickEvent(
            code,
            clock.millis(),
            classifyUserAgent(request.getHeader(HttpHeaders.USER_AGENT)),
            referrerHost(request.getHeader(HttpHeaders.REFERER))));
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, target.get())
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
        .build();
  }

  static String classifyUserAgent(String ua) {
    if (ua == null || ua.isBlank()) {
      return "unknown";
    }
    String l = ua.toLowerCase(Locale.ROOT);
    if (l.contains("bot") || l.contains("crawler") || l.contains("spider") || l.contains("curl")) {
      return "bot";
    }
    if (l.contains("mobile") || l.contains("android") || l.contains("iphone")) {
      return "mobile";
    }
    return "desktop";
  }

  static String referrerHost(String referer) {
    if (referer == null || referer.isBlank()) {
      return "";
    }
    try {
      String host = new URI(referer).getHost();
      return host == null ? "" : host.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      return "";
    }
  }
}
