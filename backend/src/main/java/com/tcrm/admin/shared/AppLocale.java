package com.tcrm.admin.shared;

import java.util.Locale;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The two languages the backend produces (ADR-0.15): {@code zh-CN} and {@code en-US}. English is
 * the default and the answer for anything else. The tag form ({@link #tag()}) is what {@code
 * crm_task.locale} stores.
 */
public enum AppLocale {
  ZH_CN(Locale.SIMPLIFIED_CHINESE, "zh-CN"),
  EN_US(Locale.ENGLISH, "en-US");

  private final Locale locale;
  private final String tag;

  AppLocale(Locale locale, String tag) {
    this.locale = locale;
    this.tag = tag;
  }

  public Locale locale() {
    return locale;
  }

  public String tag() {
    return tag;
  }

  /**
   * Maps any locale to the supported one: every {@code zh} becomes {@code zh-CN}, the rest English.
   */
  public static AppLocale support(Locale candidate) {
    return candidate != null && "zh".equalsIgnoreCase(candidate.getLanguage()) ? ZH_CN : EN_US;
  }

  /** Maps a stored {@code crm_task.locale} tag; anything but {@code en-US} is {@code zh-CN}. */
  public static AppLocale ofTag(String tag) {
    return EN_US.tag().equals(tag) ? EN_US : ZH_CN;
  }

  /**
   * The language of the current request, as resolved by the {@code localeResolver}. Without a
   * request locale (background threads, startup) the answer is English, never the JVM default.
   */
  public static AppLocale current() {
    LocaleContext context = LocaleContextHolder.getLocaleContext();
    Locale current = context == null ? null : context.getLocale();
    return current != null ? support(current) : EN_US;
  }
}
