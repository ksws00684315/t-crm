package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClickEventTest {
  static final String HASH = "a".repeat(64);

  static Map<String, String> fields() {
    Map<String, String> f = new HashMap<>();
    f.put("kolItemId", "77");
    f.put("kolCode", "KOL0001");
    f.put("productCode", "P1");
    f.put("ts", "1759708800123");
    f.put("ipHash", HASH);
    f.put("ua", "Mozilla/5.0");
    f.put("ref", "https://instagram.com/");
    f.put("country", "US");
    return f;
  }

  @Test
  void shouldParseRedirectEvent() {
    var event = ClickEvent.parse("1759708800124-3", fields()).orElseThrow();
    assertThat(event.id()).isEqualTo(1_759_708_800_124_000_003L);
    assertThat(event.clickMillis()).isEqualTo(1_759_708_800_123L);
    assertThat(event.kolItemId()).isEqualTo(77L);
    assertThat(event.ipHash()).isEqualTo(HASH);
    assertThat(event.ua()).isEqualTo("Mozilla/5.0");
    assertThat(event.referer()).isEqualTo("https://instagram.com/");
    assertThat(event.country()).isEqualTo("US");
    assertThat(event.lastClickKey()).isEqualTo("click:last:77:" + HASH);
  }

  @Test
  void shouldTruncateAndDefaultOptionalFields() {
    var f = fields();
    f.put("ua", "u".repeat(600));
    f.remove("ref");
    f.put("country", "ABCDEFGHIJ");
    f.put("ipHash", "A".repeat(64));
    var event = ClickEvent.parse("1-1", f).orElseThrow();
    assertThat(event.ua()).hasSize(512);
    assertThat(event.referer()).isEmpty();
    assertThat(event.country()).isEqualTo("ABCDEFGH");
    assertThat(event.ipHash()).isEqualTo(HASH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"kolItemId", "ts", "ipHash"})
  void shouldReject_whenRequiredFieldMissing(String field) {
    var f = fields();
    f.remove(field);
    assertThat(ClickEvent.parse("1-1", f)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1.5", "99999999999999999999", ""})
  void shouldReject_whenNumberMalformed(String value) {
    var f = fields();
    f.put("kolItemId", value);
    assertThat(ClickEvent.parse("1-1", f)).isEmpty();
    f = fields();
    f.put("ts", value);
    assertThat(ClickEvent.parse("1-1", f)).isEmpty();
  }

  @Test
  void shouldReject_whenHashOrIdMalformed() {
    var f = fields();
    f.put("ipHash", "203.0.113.1");
    assertThat(ClickEvent.parse("1-1", f)).isEmpty();
    assertThat(ClickEvent.parse("bad", fields())).isEmpty();
    assertThat(ClickEvent.parse("1-1", null)).isEmpty();
  }
}
