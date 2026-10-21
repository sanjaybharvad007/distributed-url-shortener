package com.urlshortener.api.click;

import java.util.Map;

/**
 * A redirect event as carried on the {@code clicks} Redis Stream. Field names are shared with the
 * Go redirector ({@code internal/clicks/publisher.go}).
 */
public record ClickEvent(String code, long timestampMillis, String uaClass, String referrerHost) {

  public static final String F_CODE = "code";
  public static final String F_TS = "ts";
  public static final String F_UA = "ua";
  public static final String F_REF = "ref";

  public Map<String, String> toFields() {
    return Map.of(
        F_CODE,
        code,
        F_TS,
        String.valueOf(timestampMillis),
        F_UA,
        uaClass == null ? "" : uaClass,
        F_REF,
        referrerHost == null ? "" : referrerHost);
  }

  public static ClickEvent fromFields(Map<String, String> f) {
    String ts = f.getOrDefault(F_TS, "0");
    return new ClickEvent(
        f.get(F_CODE), ts.isEmpty() ? 0L : Long.parseLong(ts), f.get(F_UA), f.get(F_REF));
  }
}
