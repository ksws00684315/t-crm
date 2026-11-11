package com.tcrm.admin.kol.service;

import java.security.SecureRandom;

/**
 * Random passwords for the super-administrator reset (task 147): 16 characters from letters and
 * digits without the easily confused {@code 0 1 O I L l i o}, drawn one character at a time from
 * {@link SecureRandom} (no modulo bias). The result satisfies the password rules every setter
 * enforces ({@code @Size(10..72) @Pattern([\x20-\x7E]+)} on the KOL credential requests).
 */
final class RandomPasswords {
  static final int LENGTH = 16;
  static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RandomPasswords() {}

  /** A new 16-character password. */
  static String next() {
    StringBuilder out = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }
}
