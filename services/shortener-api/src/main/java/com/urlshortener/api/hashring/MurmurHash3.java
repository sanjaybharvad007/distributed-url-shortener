package com.urlshortener.api.hashring;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 x86_32 (Austin Appleby, public domain). Byte-for-byte identical to the Go
 * implementation in the redirector; both are pinned by shared test vectors.
 */
public final class MurmurHash3 {

  private static final int C1 = 0xcc9e2d51;
  private static final int C2 = 0x1b873593;

  private MurmurHash3() {}

  public static int hash32(String key) {
    return hash32(key.getBytes(StandardCharsets.UTF_8), 0);
  }

  public static int hash32(byte[] data, int seed) {
    int h1 = seed;
    int len = data.length;
    int nblocks = len / 4;

    for (int i = 0; i < nblocks; i++) {
      int idx = i * 4;
      int k1 =
          (data[idx] & 0xff)
              | ((data[idx + 1] & 0xff) << 8)
              | ((data[idx + 2] & 0xff) << 16)
              | ((data[idx + 3] & 0xff) << 24);
      k1 *= C1;
      k1 = Integer.rotateLeft(k1, 15);
      k1 *= C2;

      h1 ^= k1;
      h1 = Integer.rotateLeft(h1, 13);
      h1 = h1 * 5 + 0xe6546b64;
    }

    int k1 = 0;
    int tail = nblocks * 4;
    switch (len & 3) {
      case 3:
        k1 ^= (data[tail + 2] & 0xff) << 16;
        // fallthrough
      case 2:
        k1 ^= (data[tail + 1] & 0xff) << 8;
        // fallthrough
      case 1:
        k1 ^= (data[tail] & 0xff);
        k1 *= C1;
        k1 = Integer.rotateLeft(k1, 15);
        k1 *= C2;
        h1 ^= k1;
        break;
      default:
        break;
    }

    h1 ^= len;
    h1 ^= h1 >>> 16;
    h1 *= 0x85ebca6b;
    h1 ^= h1 >>> 13;
    h1 *= 0xc2b2ae35;
    h1 ^= h1 >>> 16;
    return h1;
  }

  /** Unsigned view of {@link #hash32(String)} as a long in {@code [0, 2^32)}. */
  public static long hash32Unsigned(String key) {
    return hash32(key) & 0xffffffffL;
  }
}
