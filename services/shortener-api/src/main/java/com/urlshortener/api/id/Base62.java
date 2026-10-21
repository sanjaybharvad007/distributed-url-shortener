package com.urlshortener.api.id;

import java.util.regex.Pattern;

/** Base62 ({@code 0-9A-Za-z}) codec for non-negative longs. */
public final class Base62 {

  public static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  public static final Pattern VALID = Pattern.compile("^[0-9A-Za-z]{4,32}$");
  private static final int BASE = ALPHABET.length();
  private static final int[] DECODE = new int[128];

  static {
    java.util.Arrays.fill(DECODE, -1);
    for (int i = 0; i < ALPHABET.length(); i++) {
      DECODE[ALPHABET.charAt(i)] = i;
    }
  }

  private Base62() {}

  public static String encode(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("value must be non-negative");
    }
    if (value == 0) {
      return "0";
    }
    StringBuilder sb = new StringBuilder(11);
    while (value > 0) {
      sb.append(ALPHABET.charAt((int) (value % BASE)));
      value /= BASE;
    }
    return sb.reverse().toString();
  }

  public static long decode(String s) {
    if (s == null || s.isEmpty()) {
      throw new IllegalArgumentException("input must not be empty");
    }
    long result = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      int digit = c < 128 ? DECODE[c] : -1;
      if (digit < 0) {
        throw new IllegalArgumentException("invalid Base62 character: '" + c + "'");
      }
      result = Math.multiplyExact(result, BASE) + digit;
    }
    return result;
  }

  public static boolean isValidCode(String s) {
    return s != null && VALID.matcher(s).matches();
  }
}
