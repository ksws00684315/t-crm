package com.tcrm.admin.kol.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Plaintext copy-token format and hashing (task 17a §4.2): {@code TKN-} plus 26 uppercase Base32
 * characters from 128 bits of {@link SecureRandom}.
 */
final class CopyTokenPlaintexts {
  private static final String PREFIX = "TKN-";
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final int RANDOM_BYTES = 16;
  private static final int BODY_LEN = 26;

  private final SecureRandom random;

  CopyTokenPlaintexts() {
    this(new SecureRandom());
  }

  CopyTokenPlaintexts(SecureRandom random) {
    this.random = random;
  }

  /** {@code TKN-} + 26 Base32 chars. */
  String generate() {
    byte[] bits = new byte[RANDOM_BYTES];
    random.nextBytes(bits);
    return PREFIX + encodeBase32(bits);
  }

  /** Masked hint: {@code TKN-} + first 4 of the body + {@code ****} + last 4. */
  static String hint(String plaintext) {
    if (plaintext == null || plaintext.length() < PREFIX.length() + 8) {
      throw new IllegalArgumentException("token too short for hint");
    }
    String body = plaintext.substring(PREFIX.length());
    return PREFIX + body.substring(0, 4) + "****" + body.substring(body.length() - 4);
  }

  static String sha256Hex(String plaintext) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** True when the value matches the issued format (prefix + 26 Base32). */
  static boolean looksValid(String plaintext) {
    if (plaintext == null || plaintext.length() != PREFIX.length() + BODY_LEN) {
      return false;
    }
    if (!plaintext.startsWith(PREFIX)) {
      return false;
    }
    for (int i = PREFIX.length(); i < plaintext.length(); i++) {
      char c = plaintext.charAt(i);
      if ((c < 'A' || c > 'Z') && (c < '2' || c > '7')) {
        return false;
      }
    }
    return true;
  }

  private static String encodeBase32(byte[] bits) {
    // 128 bits → 26 Base32 chars (last char uses 3 leftover bits, zero-padded).
    StringBuilder out = new StringBuilder(BODY_LEN);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : bits) {
      buffer = (buffer << 8) | (b & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        out.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1f]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      out.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
    }
    return out.toString();
  }
}
