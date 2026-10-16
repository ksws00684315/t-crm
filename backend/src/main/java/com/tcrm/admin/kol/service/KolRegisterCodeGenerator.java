package com.tcrm.admin.kol.service;

import java.security.SecureRandom;

/** 32-character Base62 register codes (task 12c). {@link SecureRandom} supplies the bytes. */
final class KolRegisterCodeGenerator {
  static final int LENGTH = 32;
  private static final char[] ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
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
}
