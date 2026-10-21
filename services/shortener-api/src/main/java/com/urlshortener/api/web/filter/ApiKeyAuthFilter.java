package com.urlshortener.api.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates management-API calls with an {@code X-API-Key} header. Keys are compared by
 * constant-time digest comparison and the caller's identity (a short hash of the key) is exposed as
 * the request attribute {@link #OWNER_ATTR} and in the MDC for logging.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-API-Key";
  public static final String OWNER_ATTR = "ownerId";

  private final Map<String, String> ownerByKeyDigest;

  public ApiKeyAuthFilter(Collection<String> apiKeys) {
    this.ownerByKeyDigest =
        apiKeys.stream()
            .map(String::strip)
            .filter(k -> !k.isEmpty())
            .collect(
                Collectors.toUnmodifiableMap(
                    ApiKeyAuthFilter::digest, ApiKeyAuthFilter::ownerId, (a, b) -> a));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    String owner = key == null ? null : ownerByKeyDigest.get(digest(key));
    if (owner == null) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                  + "\"detail\":\"missing or invalid X-API-Key\"}");
      return;
    }
    request.setAttribute(OWNER_ATTR, owner);
    MDC.put("apiKeyId", owner);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("apiKeyId");
    }
  }

  /** Stable, non-reversible owner identifier derived from the key. */
  static String ownerId(String apiKey) {
    return "k_" + digest(apiKey).substring(0, 16);
  }

  static String digest(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
