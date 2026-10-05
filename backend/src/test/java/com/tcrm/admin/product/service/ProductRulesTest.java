package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductRulesTest {
  @Test
  void shouldFormatCode_withAtLeastSixDigits() {
    assertThat(ProductRules.formatCode(1)).isEqualTo("P000001");
    assertThat(ProductRules.formatCode(999999)).isEqualTo("P999999");
    assertThat(ProductRules.formatCode(1000000)).isEqualTo("P1000000");
  }

  @ParameterizedTest
  @ValueSource(strings = {"P000001", "ABC", "A-B_1", "1234567890123456"})
  void shouldAcceptValidCodes(String code) {
    assertThat(ProductRules.isCode(code)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "p000001", "A B", "工具", "12345678901234567"})
  void shouldRejectInvalidCodes(String code) {
    assertThat(ProductRules.isCode(code)).isFalse();
  }

  @Test
  void shouldParsePrice_ignoringThousandsSeparators() {
    assertThat(ProductRules.parsePrice("1,234.5")).isEqualByComparingTo("1234.50");
    assertThat(ProductRules.parsePrice("-1")).isNull();
    assertThat(ProductRules.parsePrice("1.234")).isNull();
  }

  @Test
  void shouldConvertPercentToRate() {
    assertThat(ProductRules.percentToRate("15.5")).isEqualByComparingTo("0.1550");
    assertThat(ProductRules.percentToRate("100")).isEqualByComparingTo("1.0000");
    assertThat(ProductRules.percentToRate("100.01")).isNull();
  }

  @Test
  void shouldFormatPriceAndRateTexts() {
    assertThat(ProductRules.priceText(new BigDecimal("12.5"))).isEqualTo("12.50");
    assertThat(ProductRules.rateText(new BigDecimal("0.155"))).isEqualTo("0.1550");
    assertThat(ProductRules.rateText(null)).isNull();
  }

  @ParameterizedTest
  @CsvSource({"https://a.com/x,true", "http://a.com,false", "'',false"})
  void shouldValidateHttpsUrl(String url, boolean ok) {
    assertThat(ProductRules.isHttpsUrl(url)).isEqualTo(ok);
  }
}
