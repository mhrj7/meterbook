package com.meterbook.identity.web;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Request IDs in the form {@code req_<26 chars>}: a ULID (48-bit millisecond timestamp, 80 random
 * bits, Crockford base32), so IDs sort by time in logs and are unguessable.
 */
public final class RequestIds {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final String PREFIX = "req_";

  private final Clock clock;
  private final SecureRandom random;

  public RequestIds(Clock clock, SecureRandom random) {
    this.clock = clock;
    this.random = random;
  }

  public String next() {
    long time = clock.millis();
    byte[] entropy = new byte[10];
    random.nextBytes(entropy);
    char[] out = new char[26];
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 31)];
      time >>>= 5;
    }
    // 80 random bits = 16 base32 characters, taken 5 bits at a time from the byte array.
    int bitBuffer = 0;
    int bits = 0;
    int pos = 10;
    for (byte b : entropy) {
      bitBuffer = (bitBuffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out[pos++] = ALPHABET[(bitBuffer >>> (bits - 5)) & 31];
        bits -= 5;
      }
    }
    return PREFIX + new String(out);
  }

  /** Accepts a caller-supplied ID only if it looks like one of ours, so logs cannot be polluted. */
  public static boolean isValid(String candidate) {
    if (candidate == null
        || candidate.length() != PREFIX.length() + 26
        || !candidate.startsWith(PREFIX)) {
      return false;
    }
    for (int i = PREFIX.length(); i < candidate.length(); i++) {
      if (java.util.Arrays.binarySearch(ALPHABET, candidate.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }
}
