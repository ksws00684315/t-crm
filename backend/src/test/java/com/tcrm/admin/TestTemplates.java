package com.tcrm.admin;

import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Renders import template header keys into header texts for tests, through the production message
 * bundles (Chinese by default, matching the historical templates).
 */
public final class TestTemplates {
  private static final MessageSource MESSAGES = create();

  private TestTemplates() {}

  private static MessageSource create() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasenames("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return source;
  }

  public static List<String> render(List<String> keys) {
    return render(keys, Locale.SIMPLIFIED_CHINESE);
  }

  public static List<String> render(List<String> keys, Locale locale) {
    return keys.stream().map(key -> MESSAGES.getMessage(key, null, locale)).toList();
  }
}
