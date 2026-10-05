package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClickRulesTest {
  @Test
  void shouldCombineMillisAndSequence_whenStreamIdValid() {
    assertThat(ClickRules.clickId("1759708800000-0")).hasValue(1_759_708_800_000_000_000L);
    assertThat(ClickRules.clickId("1759708800000-42")).hasValue(1_759_708_800_000_000_042L);
    assertThat(ClickRules.clickId("1759708800000-999999")).hasValue(1_759_708_800_000_999_999L);
    assertThat(ClickRules.clickId("0-1")).hasValue(1L);
  }

  @Test
  void shouldKeepOrderAndUniqueness_acrossMessages() {
    long a = ClickRules.clickId("1759708800000-999999").getAsLong();
    long b = ClickRules.clickId("1759708800001-0").getAsLong();
    assertThat(b).isGreaterThan(a);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "0-0",
        "1759708800000-1000000",
        "1759708800000",
        "abc-1",
        "1-2-3",
        "-1-0",
        "9223372036854-775808",
        "9223372036855-0",
        "99999999999999999999-0"
      })
  void shouldRejectStreamId_whenNotMappable(String id) {
    assertThat(ClickRules.clickId(id)).isEmpty();
  }

  @Test
  void shouldAcceptLargestMappableId() {
    assertThat(ClickRules.clickId("9223372036854-775807")).hasValue(Long.MAX_VALUE);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "Googlebot/2.1",
        "Mozilla/5.0 (compatible; bingbot/2.0)",
        "Baiduspider",
        "SomeCrawler/1.0",
        "facebookexternalhit/1.1",
        "facebookcatalog/1.0",
        "Twitterbot/1.0",
        "Slackbot-LinkExpanding 1.0",
        "WhatsApp/2.23",
        "TelegramBot (like TwitterBot)",
        "Mozilla/5.0 (compatible; Discordbot/2.0)",
        "LinkedInBot/1.0",
        "Pinterest/0.2",
        "Mozilla/5.0 TikTok 30.1",
        "Bytespider",
        "Mozilla/5.0 Link Preview",
        "Mozilla/5.0 HeadlessChrome/120.0"
      })
  void shouldDetectBot(String ua) {
    assertThat(ClickRules.isBot(ua)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36"
      })
  void shouldAcceptBrowser(String ua) {
    assertThat(ClickRules.isBot(ua)).isFalse();
  }

  @Test
  void shouldKeepKeywordsInOnePlace() {
    assertThat(ClickRules.BOT_KEYWORDS).hasSize(16).allMatch(k -> k.equals(k.toLowerCase()));
  }

  @Test
  void shouldFlagDup_onlyWithinTenSeconds() {
    assertThat(ClickRules.isDup(null, 10_000)).isFalse();
    assertThat(ClickRules.isDup(1_000L, 1_000)).isTrue();
    assertThat(ClickRules.isDup(1_000L, 10_999)).isTrue();
    assertThat(ClickRules.isDup(1_000L, 11_000)).isFalse();
    assertThat(ClickRules.isDup(10_999L, 1_000)).isTrue();
    assertThat(ClickRules.isDup(11_000L, 1_000)).isFalse();
  }

  @Test
  void shouldTruncateToColumnLength() {
    assertThat(ClickRules.truncate(null, 8)).isEmpty();
    assertThat(ClickRules.truncate("US", 8)).isEqualTo("US");
    assertThat(ClickRules.truncate("123456789", 8)).isEqualTo("12345678");
  }
}
