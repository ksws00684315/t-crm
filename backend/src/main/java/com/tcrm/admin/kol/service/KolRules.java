package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Field rules shared by the KOL API and the Excel import (task 04 §4.1, §4.5). */
public final class KolRules {
  /** KOL code format (ADR-023), identical to the redirect service check. */
  public static final String CODE_PATTERN = "KOL-[A-Z]{3}[0-9]{3,8}";

  /** Pragmatic e-mail shape: local part, "@", at least two dot-separated DNS labels. */
  public static final String EMAIL_PATTERN =
      "[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
          + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+";

  /** Social profile URL: https with a host. */
  public static final String HTTPS_URL_PATTERN = "https://[^\\s/?#]+\\S*";

  /** WhatsApp number, E.164: a plus followed by 8 to 15 digits (task 11 §4.3). */
  public static final String WHATSAPP_PATTERN = "\\+[0-9]{8,15}";

  /** Invite code on the agent platform, 1 to 64 URL-safe characters (task 11 §4.3). */
  public static final String INVITE_CODE_PATTERN = "[A-Za-z0-9_-]{1,64}";

  /** Promotion sheet URL prefix, same as the database CHECK (task 11 §4.3). */
  public static final String SHEET_URL_PATTERN = "https://docs\\.google\\.com/spreadsheets/.*";

  public static final int MAX_NAME = 64;
  public static final int MAX_EMAIL = 128;
  public static final int MAX_PLATFORM = 32;
  public static final int MAX_HANDLE = 128;
  public static final int MAX_URL = 512;
  public static final int MAX_REMARK = 500;
  public static final int MAX_SOCIAL_ACCOUNTS = 10;
  public static final int MAX_WHATSAPP = 32;
  public static final int MAX_INVITE_CODE = 64;
  public static final int MAX_SHEET_URL = 512;

  private static final Pattern CODE = Pattern.compile(CODE_PATTERN);
  private static final Pattern EMAIL = Pattern.compile(EMAIL_PATTERN);
  private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(?:\\.\\d{1,2})?");
  private static final Pattern COUNT = Pattern.compile("\\d{1,15}");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Set<String> COUNTRIES =
      Set.of(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).toArray(String[]::new));

  private KolRules() {}

  /**
   * Formats a KOL code: {@code KOL-} + 3 uppercase letters + sequence number padded to at least 3
   * digits (ADR-023). Example: {@code KOL-ASD001}, {@code KOL-QWE1234}.
   */
  public static String formatCode(String letters, long number) {
    if (letters == null || !letters.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("letters must be 3 uppercase A-Z");
    }
    String digits = Long.toString(number);
    if (digits.length() < 3) {
      digits = String.format(Locale.ROOT, "%03d", number);
    }
    return "KOL-" + letters + digits;
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
