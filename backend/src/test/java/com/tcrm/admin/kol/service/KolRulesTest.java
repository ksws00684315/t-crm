package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KolRulesTest {
  @ParameterizedTest
  @CsvSource({"1, KOL-ASD001", "999, KOL-ASD999", "1000, KOL-ASD1000", "99999999, KOL-ASD99999999"})
  void shouldPadToThreeDigitsAndWiden_whenFormattingCode(long number, String code) {
    assertThat(KolRules.formatCode("ASD", number)).isEqualTo(code);
    assertThat(KolRules.isCode(code)).isTrue();
  }

  @Test
  void shouldRejectLetters_whenNotThreeUppercase() {
    assertThatThrownBy(() -> KolRules.formatCode("as", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "KOL0001",
        "KOL-asd001",
        "KOL-AS001",
        "KOL-ASD01",
        "KOL-ASD123456789",
        "XKOL-ASD001",
        ""
      })
  void shouldRejectCode_whenFormatIsWrong(String code) {
    assertThat(KolRules.isCode(code)).isFalse();
    assertThat(KolRules.isCode(null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"a@example.com", "first.last+tag@sub.example.co", "A_B%c-d@x-y.io"})
  void shouldAcceptEmail_whenShapeIsValid(String email) {
    assertThat(KolRules.isEmail(email)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"plain", "a@localhost", "a@-x.com", "a@x-.com", "a b@x.com", "a@x..com", "@x.com"})
  void shouldRejectEmail_whenShapeIsInvalid(String email) {
    assertThat(KolRules.isEmail(email)).isFalse();
    assertThat(KolRules.isEmail(null)).isFalse();
  }

  @Test
  void shouldAcceptOnlyAssignedIsoAlpha2Codes_whenCheckingCountry() {
    assertThat(KolRules.isCountry("US")).isTrue();
    assertThat(KolRules.isCountry("cn")).isTrue();
    assertThat(KolRules.isCountry("XX")).isFalse();
    assertThat(KolRules.isCountry("USA")).isFalse();
    assertThat(KolRules.isCountry(null)).isFalse();
  }

  @Test
  void shouldCountCodePoints_whenMeasuringLength() {
    assertThat(KolRules.length("达人😀")).isEqualTo(3);
    assertThat(KolRules.length(null)).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "15.5, 0.1550",
    "0, 0.0000",
    "100, 1.0000",
    "100.00, 1.0000",
    "12.34%, 0.1234",
    "'7 %', 0.0700",
    "0.01, 0.0001"
  })
  void shouldConvertPercentToFraction_whenPercentIsValid(String text, String rate) {
    assertThat(KolRules.percentToRate(text)).isEqualByComparingTo(new BigDecimal(rate));
    assertThat(KolRules.percentToRate(text).scale()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"100.01", "-1", "1.234", "abc", "1e2", ".5", "1000", "%"})
  void shouldReturnNull_whenPercentIsInvalid(String text) {
    assertThat(KolRules.percentToRate(text)).isNull();
  }

  @Test
  void shouldRenderFourDecimals_whenFormattingRate() {
    assertThat(KolRules.rateText(new BigDecimal("0.155"))).isEqualTo("0.1550");
    assertThat(KolRules.rateText(new BigDecimal("1"))).isEqualTo("1.0000");
    assertThat(KolRules.rateText(new BigDecimal("1E-4"))).isEqualTo("0.0001");
    assertThat(KolRules.rateText(null)).isNull();
  }

  @Test
  void shouldParseFollowerCount_whenTextIsNonNegativeInteger() {
    assertThat(KolRules.parseCount("12,000")).isEqualTo(12_000L);
    assertThat(KolRules.parseCount("0")).isZero();
    for (String bad : List.of("-1", "1.5", "abc", "1234567890123456", "")) {
      assertThat(KolRules.parseCount(bad)).as(bad).isNull();
    }
  }

  @Test
  void shouldExposeTemplateHeadersInDocumentedOrder() {
    assertThat(com.tcrm.admin.TestTemplates.render(KolImportColumn.headerKeys()))
        .containsExactly(
            "达人编号",
            "姓名",
            "邮箱",
            "国家",
            "负责商务用户名",
            "默认佣金比例(%)",
            "社媒平台",
            "社媒账号",
            "粉丝数",
            "备注",
            "WhatsApp",
            "代购平台编码",
            "邀请码",
            "推广表格地址");
    assertThat(
            com.tcrm.admin.TestTemplates.render(
                KolImportColumn.headerKeys(), java.util.Locale.ENGLISH))
        .containsExactly(
            "KOL Code",
            "Name",
            "Email",
            "Country",
            "BD Username",
            "Default Commission Rate (%)",
            "Social Platform",
            "Social Handle",
            "Followers",
            "Remark",
            "WhatsApp",
            "Agent Template Code",
            "Invite Code",
            "Promotion Sheet URL");
    assertThat(com.tcrm.admin.TestTemplates.render(KolImportColumn.legacyHeaderKeys()))
        .containsExactly(
            "达人编号", "姓名", "邮箱", "国家", "负责商务用户名", "默认佣金比例(%)", "社媒平台", "社媒账号", "粉丝数", "备注");
  }

  @Test
  void shouldRoundTripSocialAccountsWithNumericFollowers() {
    var accounts = List.of(new SocialAccountDTO("TikTok", "@a", null, 12_000L));
    String json = SocialAccountsJson.write(accounts);
    assertThat(json).contains("\"followers\":12000");
    assertThat(SocialAccountsJson.read(json)).isEqualTo(accounts);
    assertThat(SocialAccountsJson.write(null)).isEqualTo("[]");
    assertThat(SocialAccountsJson.read(null)).isEmpty();
    assertThat(SocialAccountsJson.read(" ")).isEmpty();
  }
}
