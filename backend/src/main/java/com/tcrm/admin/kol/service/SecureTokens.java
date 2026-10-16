package com.tcrm.admin.kol.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-time credential tokens (task 12a §4.4): 32 random bytes, sent Base64URL, stored as SHA-256.
 */
public final class SecureTokens {
  private static final SecureRandom RANDOM = new SecureRandom();

  private SecureTokens() {}

  /** New 32-byte random token, Base64URL encoded without padding (43 characters). */
  public static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** SHA-256 hex digest (64 characters) of the raw token; the only value stored in the database. */
  public static String hash(String token) {
    return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
