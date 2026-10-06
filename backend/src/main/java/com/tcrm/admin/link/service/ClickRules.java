package com.tcrm.admin.link.service;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Pure click rules of task 06 §4.5: click id from the stream message ID, bot and dup checks. */
public final class ClickRules {
  /**
   * User-Agent keywords (case-insensitive) of crawlers and social media link previews; an empty UA
   * is a bot too. The single place to extend the list.
   */
  public static final List<String> BOT_KEYWORDS =
      List.of(
          "bot",
          "spider",
          "crawl",
          "facebookexternalhit",
          "facebookcatalog",
          "twitterbot",
          "slackbot",
          "whatsapp",
          "telegrambot",
          "discordbot",
          "linkedinbot",
          "pinterest",
          "tiktok",
          "bytespider",
          "preview",
          "headless");

  /** Clicks of the same link and IP hash closer than this (event time) are duplicates. */
  public static final long DUP_WINDOW_MILLIS = 10_000;

  /** Sequence numbers per millisecond are below this so that ids never collide. */
  static final long SEQUENCE_LIMIT = 1_000_000;

  private static final Pattern STREAM_ID = Pattern.compile("^(\\d{1,19})-(\\d{1,19})$");

  private ClickRules() {}

  /**
   * Click id = milliseconds × 1,000,000 + sequence of the stream message ID ({@code ms-seq}). The
   * same message always yields the same positive id; empty when the ID cannot be mapped (malformed,
   * zero, sequence ≥ 1,000,000 or overflow).
   */
  public static OptionalLong clickId(String streamId) {
    var matcher = STREAM_ID.matcher(streamId == null ? "" : streamId);
    if (!matcher.matches()) {
      return OptionalLong.empty();
    }
    long millis;
    long sequence;
    try {
      millis = Long.parseLong(matcher.group(1));
      sequence = Long.parseLong(matcher.group(2));
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
    if (sequence >= SEQUENCE_LIMIT || millis > (Long.MAX_VALUE - sequence) / SEQUENCE_LIMIT) {
      return OptionalLong.empty();
    }
    long id = millis * SEQUENCE_LIMIT + sequence;
    return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
  }

  /** Empty (or blank) UA, or a UA containing any {@link #BOT_KEYWORDS} entry. */
  public static boolean isBot(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return true;
    }
    String ua = userAgent.toLowerCase(Locale.ROOT);
    return BOT_KEYWORDS.stream().anyMatch(ua::contains);
  }

  /** True when the previous click of the same link and IP hash is less than 10 s away. */
  public static boolean isDup(Long previousMillis, long clickMillis) {
    return previousMillis != null && Math.abs(clickMillis - previousMillis) < DUP_WINDOW_MILLIS;
  }

  /** Cuts a value to the column length; null becomes empty. */
  public static String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
