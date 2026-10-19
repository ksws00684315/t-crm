package com.tcrm.admin.kol.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 8-character register / invite codes (task 29 / ADR-025). Alphabet is uppercase letters and digits
 * without {@code 0 O 1 I L}. Input is matched case-insensitively after normalizing to uppercase.
 */
final class KolRegisterCodeGenerator {
  static final int LENGTH = 8;
  private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private KolRegisterCodeGenerator() {}

  /** A new code. The unique index rejects the rare collision; the caller retries. */
  static String next() {
    char[] out = new char[LENGTH];
    for (int i = 0; i < LENGTH; i++) {
      out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(out);
  }

  /** Uppercases and strips; returns null when blank. Does not validate charset. */
  static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.strip().toUpperCase(Locale.ROOT);
  }
}
