package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Field rules shared by the KOL API and the Excel import (task 04 §4.1, §4.5). */
public final class KolRules {
  /** KOL code format, identical to the redirect service check. */
  public static final String CODE_PATTERN = "KOL\\d{4,8}";

  /** Pragmatic e-mail shape: local part, "@", at least two dot-separated DNS labels. */
  public static final String EMAIL_PATTERN =
      "[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
          + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+";

  /** Social profile URL: https with a host. */
  public static final String HTTPS_URL_PATTERN = "https://[^\\s/?#]+\\S*";

  public static final int MAX_NAME = 64;
  public static final int MAX_EMAIL = 128;
  public static final int MAX_PLATFORM = 32;
  public static final int MAX_HANDLE = 128;
  public static final int MAX_URL = 512;
  public static final int MAX_REMARK = 500;
  public static final int MAX_SOCIAL_ACCOUNTS = 10;

  private static final Pattern CODE = Pattern.compile(CODE_PATTERN);
  private static final Pattern EMAIL = Pattern.compile(EMAIL_PATTERN);
  private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
  private static final Pattern COUNT = Pattern.compile("\\d{1,15}");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Set<String> COUNTRIES =
      Set.of(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).toArray(String[]::new));

  private KolRules() {}

  /** Formats a sequence number as a KOL code: at least 4 digits, zero-padded (KOL0001). */
  public static String formatCode(long number) {
    return String.format(Locale.ROOT, "KOL%04d", number);
  }

  public static boolean isCode(String value) {
    return value != null && CODE.matcher(value).matches();
  }

  public static boolean isEmail(String value) {
    return value != null && EMAIL.matcher(value).matches();
  }

  /** ISO 3166-1 alpha-2 code, case-insensitive (stored upper case). */
  public static boolean isCountry(String value) {
    return value != null && COUNTRIES.contains(value.toUpperCase(Locale.ROOT));
  }

  /** Length in Unicode characters, matching PostgreSQL VARCHAR(n) semantics. */
  public static int length(String value) {
    return value == null ? 0 : value.codePointCount(0, value.length());
  }

  /**
   * Converts a percentage text (0 to 100, at most 2 decimals, optional trailing "%") to the stored
   * fraction with 4 decimals: "15.5" becomes 0.1550.
   *
   * @return the fraction, or null when the text is not a valid percentage
   */
  public static BigDecimal percentToRate(String text) {
    String value = text.endsWith("%") ? text.substring(0, text.length() - 1).strip() : text;
    if (!PERCENT.matcher(value).matches()) {
      return null;
    }
    BigDecimal percent = new BigDecimal(value);
    if (percent.compareTo(HUNDRED) > 0) {
      return null;
    }
    return percent.divide(HUNDRED, 4, RoundingMode.HALF_UP);
  }

  /** Rate as API text with exactly 4 decimals ("0.1550"); null stays null. */
  public static String rateText(BigDecimal rate) {
    return rate == null ? null : rate.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Parses a non-negative follower count; thousands separators are ignored ("12,000").
   *
   * @return the count, or null when the text is not a non-negative integer
   */
  public static Long parseCount(String text) {
    String value = text.replace(",", "");
    return COUNT.matcher(value).matches() ? Long.valueOf(value) : null;
  }
}
