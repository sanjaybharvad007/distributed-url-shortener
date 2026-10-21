package com.urlshortener.api.validation;

import com.urlshortener.api.config.AppProperties;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates and normalises target URLs. Guards against open-redirect abuse (private / loopback /
 * metadata hosts, credentials in the URL, redirect loops back into this service).
 */
@Component
public class UrlValidator {

  public static class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
      super(message);
    }
  }

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private final int maxLength;
  private final Set<String> blockedHosts;
  private final String ownHost;

  public UrlValidator(AppProperties props) {
    this.maxLength = props.url().maxLength();
    this.blockedHosts =
        props.url().blockedHosts() == null
            ? Set.of()
            : Set.copyOf(props.url().blockedHosts().stream().map(String::toLowerCase).toList());
    this.ownHost = hostOf(props.baseUrl());
  }

  /** Returns the normalised URL or throws {@link InvalidUrlException}. */
  public String validateAndNormalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidUrlException("longUrl must not be blank");
    }
    String trimmed = raw.strip();
    if (trimmed.length() > maxLength) {
      throw new InvalidUrlException("longUrl exceeds " + maxLength + " characters");
    }
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new InvalidUrlException("longUrl is not a valid URI: " + e.getReason());
    }
    String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
      throw new InvalidUrlException("longUrl scheme must be http or https");
    }
    if (uri.getHost() == null) {
      throw new InvalidUrlException("longUrl must have a host");
    }
    if (uri.getRawUserInfo() != null) {
      throw new InvalidUrlException("longUrl must not contain credentials");
    }
    String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (isBlockedHost(host)) {
      throw new InvalidUrlException("longUrl host is not allowed");
    }
    if (ownHost != null && host.equals(ownHost)) {
      throw new InvalidUrlException("longUrl must not point back at this service");
    }

    // Normalise: lower-case scheme/host, drop default ports, keep path/query/fragment as-is.
    int port = uri.getPort();
    boolean defaultPort =
        port == -1
            || ("http".equals(scheme) && port == 80)
            || ("https".equals(scheme) && port == 443);
    StringBuilder sb = new StringBuilder(trimmed.length());
    sb.append(scheme).append("://").append(host);
    if (!defaultPort) {
      sb.append(':').append(port);
    }
    String path = uri.getRawPath();
    sb.append(path == null || path.isEmpty() ? "/" : path);
    if (uri.getRawQuery() != null) {
      sb.append('?').append(uri.getRawQuery());
    }
    if (uri.getRawFragment() != null) {
      sb.append('#').append(uri.getRawFragment());
    }
    return sb.toString();
  }

  boolean isBlockedHost(String host) {
    if (blockedHosts.contains(host)) {
      return true;
    }
    if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
      return true;
    }
    if (IPV4.matcher(host).matches()) {
      String[] p = host.split("\\.");
      int a = Integer.parseInt(p[0]);
      int b = Integer.parseInt(p[1]);
      // 10/8, 127/8, 0/8, 169.254/16, 172.16/12, 192.168/16, 100.64/10
      return a == 10
          || a == 127
          || a == 0
          || (a == 169 && b == 254)
          || (a == 172 && b >= 16 && b <= 31)
          || (a == 192 && b == 168)
          || (a == 100 && b >= 64 && b <= 127);
    }
    // IPv6 loopback / unique-local / link-local (only literal addresses contain ':').
    if (host.indexOf(':') >= 0) {
      return host.equals("::1")
          || host.startsWith("fc")
          || host.startsWith("fd")
          || host.startsWith("fe80");
    }
    return false;
  }

  private static String hostOf(String url) {
    try {
      URI u = new URI(url);
      return u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      return null;
    }
  }
}
