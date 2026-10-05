package com.tcrm.redirect.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LinkEntryTest {
  @Test
  void shouldParseJsonValue_whenStatusIsActive() {
    var entry =
        LinkEntry.parse(
            "{\"kolItemId\":\"123\",\"targetUrl\":\"https://example.com/a?x=1|2\",\"status\":1}");
    assertThat(entry).isPresent();
    assertThat(entry.get().kolItemId()).isEqualTo("123");
    assertThat(entry.get().targetUrl()).isEqualTo("https://example.com/a?x=1|2");
  }

  @Test
  void shouldAcceptNumericIdAndStringStatus_whenJsonUsesLooseTypes() {
    var entry =
        LinkEntry.parse(
            "{\"kolItemId\":456,\"targetUrl\":\"https://example.com/b\",\"status\":\"1\"}");
    assertThat(entry).isPresent();
    assertThat(entry.get().kolItemId()).isEqualTo("456");
  }

  @Test
  void shouldRejectValue_whenInactiveOrMalformed() {
    for (String value :
        new String[] {
          "",
          "9|https://example.com/item|1",
          "{\"kolItemId\":\"9\",\"targetUrl\":\"https://example.com\",\"status\":0}",
          "{\"kolItemId\":\"0\",\"targetUrl\":\"https://example.com\",\"status\":1}",
          "{\"kolItemId\":\"9\",\"status\":1}",
          "{\"kolItemId\":\"9\",\"targetUrl\":123,\"status\":1}",
          "{\"kolItemId\":\"9\",\"targetUrl\":\"https://example.com\"}",
          "{not json",
          "[1,2,3]",
          "{\"kolItemId\":{},\"targetUrl\":\"https://example.com\",\"status\":1}"
        }) {
      assertThat(LinkEntry.parse(value)).as(value).isEmpty();
    }
  }
}
