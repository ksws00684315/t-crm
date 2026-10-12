package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The request language (ADR-0.15): only zh-CN and en-US exist; a missing, unparsable or unsupported
 * Accept-Language yields English regardless of the server's default locale, and a weighted list
 * picks its first supported entry.
 */
class SupportedLocaleResolverTest {
  private final Locale originalDefault = Locale.getDefault();
  private final SupportedLocaleResolver resolver = new SupportedLocaleResolver();

  @BeforeEach
  void useChineseServerLocale() {
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
  }

  @AfterEach
  void restoreDefault() {
    Locale.setDefault(originalDefault);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "zh-CN|zh-CN",
        "zh|zh-CN",
        "zh-TW|zh-CN",
        "en-US|en",
        "en|en",
        "en-GB|en",
        "zh,en;q=0.8|zh-CN",
        "en-GB,zh-CN;q=0.9|en",
        "fr-FR,en;q=0.5|en",
        "fr-FR|en",
        "ja|en",
        "*|en"
      },
      delimiter = '|')
  void shouldResolveOnlyTheTwoSupportedLanguages(String header, String expected) {
    assertThat(resolve(header)).isEqualTo(Locale.forLanguageTag(expected));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "not a locale!!!"})
  void shouldFallBackToEnglish_whenHeaderIsMissingOrUnparsable(String header) {
    // The server default is Chinese (see @BeforeEach) and must not be used.
    assertThat(resolve(header)).isEqualTo(Locale.ENGLISH);
  }

  @Test
  void shouldRejectLocaleChanges() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThatThrownBy(() -> resolver.setLocale(request, null, Locale.SIMPLIFIED_CHINESE))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private Locale resolve(String header) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (header != null) {
      request.addHeader("Accept-Language", header);
    }
    return resolver.resolveLocale(request);
  }
}
