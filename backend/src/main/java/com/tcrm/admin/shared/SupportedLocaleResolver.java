package com.tcrm.admin.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Resolves the request language from {@code Accept-Language} into one of the two supported
 * languages (ADR-0.15). A missing, unparsable or unsupported header yields English — never the
 * server's default locale — and a weighted list picks its first supported entry, so {@code zh},
 * {@code zh-CN}, {@code en}, {@code en-US} and lists like {@code zh,en;q=0.8} all resolve.
 *
 * <p>Spring's stock {@code AcceptHeaderLocaleResolver} is not used because it maps a bare {@code
 * zh} to the default language instead of {@code zh-CN}. {@link Locale#lookup} matches on
 * language-only candidates ({@code zh}, {@code en}), so its result is folded back onto the two full
 * locales.
 */
public final class SupportedLocaleResolver implements LocaleResolver {
  /** Language-only entries make lookup work for bare {@code zh} / {@code en}. */
  private static final List<Locale> CANDIDATES =
      List.of(AppLocale.ZH_CN.locale(), Locale.CHINESE, AppLocale.EN_US.locale());

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    String header = request.getHeader("Accept-Language");
    if (header == null || header.isBlank()) {
      return AppLocale.EN_US.locale();
    }
    try {
      Locale matched = Locale.lookup(Locale.LanguageRange.parse(header), CANDIDATES);
      return matched != null ? AppLocale.support(matched).locale() : AppLocale.EN_US.locale();
    } catch (IllegalArgumentException e) {
      return AppLocale.EN_US.locale();
    }
  }

  @Override
  public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
    throw new UnsupportedOperationException("The request language cannot be changed server-side");
  }
}
