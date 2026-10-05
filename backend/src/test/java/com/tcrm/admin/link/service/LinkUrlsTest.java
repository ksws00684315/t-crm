package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkUrlsTest {
  @Test
  void shouldBuildLinkFromBaseKolAndProduct_whenBaseUrlValid() {
    assertThat(new LinkUrls("https://t.example.com").linkUrl("KOL0001", "P000123"))
        .isEqualTo("https://t.example.com/t/KOL0001/P000123");
  }

  @Test
  void shouldBuildWeidianTargetWithoutExtraParameters_whenGivenExclusiveItem() {
    assertThat(LinkUrls.targetUrl("7712345"))
        .isEqualTo("https://weidian.com/item.html?itemID=7712345");
  }

  @Test
  void shouldAcceptBaseUrlWithPath_whenNoTrailingSlash() {
    assertThat(LinkUrls.validate("https://example.com/go")).isEqualTo("https://example.com/go");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "http://t.example.com",
        "https://t.example.com/",
        "t.example.com",
        "https://user@t.example.com",
        "https://t.example.com?x=1",
        "https://t.example.com#a",
        "https://bad host"
      })
  void shouldFailNamingVariable_whenBaseUrlMissingOrMalformed(String value) {
    assertThatThrownBy(() -> new LinkUrls(value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_LINK_BASE_URL");
  }
}
