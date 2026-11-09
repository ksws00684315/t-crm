package com.tcrm.admin.kollibrary.service;

import java.util.Locale;

/**
 * Creator ID (username) as requirement 1.2.6 ① stores it: leading and trailing spaces removed, one
 * leading {@code @} removed, lower case. The normalised value identifies the creator for duplicate
 * detection, so {@code "@BeautyByAmy "}, {@code "beautybyamy"} and {@code "BEAUTYBYAMY"} are one
 * creator, while {@code "@"} alone is empty and therefore invalid.
 */
public final class LibraryUsernames {
  private LibraryUsernames() {}

  /** The normalised creator ID; empty when the input is blank or only an {@code @}. */
  public static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.strip();
    if (value.startsWith("@")) {
      value = value.substring(1).strip();
    }
    return value.toLowerCase(Locale.ROOT);
  }

  /** True when the normalised creator ID satisfies the stored format. */
  public static boolean valid(String normalized) {
    return normalized.matches("[A-Za-z0-9._]{1,64}");
  }
}
