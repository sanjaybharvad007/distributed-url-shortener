package com.urlshortener.api.web.filter;

import com.urlshortener.api.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the distributed token bucket per API key (falls back to client IP for unauthenticated
 * paths) and emits IETF {@code RateLimit-*} headers.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimiter limiter;
  private final boolean enabled;

  public RateLimitFilter(RateLimiter limiter, boolean enabled) {
    this.limiter = limiter;
    this.enabled = enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!enabled) {
      chain.doFilter(request, response);
      return;
    }
    Object owner = request.getAttribute(ApiKeyAuthFilter.OWNER_ATTR);
    String clientKey = owner != null ? owner.toString() : "ip:" + clientIp(request);
    RateLimiter.Decision d = limiter.check(clientKey);

    response.setHeader("RateLimit-Limit", String.valueOf(d.limit()));
    response.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
    response.setHeader("RateLimit-Reset", String.valueOf(d.resetSeconds()));
    if (d.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    long retrySeconds = Math.max(1, (d.retryAfterMillis() + 999) / 1000);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(retrySeconds));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"rate limit exceeded, retry in "
                + retrySeconds
                + "s\"}");
  }

  static String clientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      int comma = xff.indexOf(',');
      return (comma > 0 ? xff.substring(0, comma) : xff).strip();
    }
    return request.getRemoteAddr();
  }
}
