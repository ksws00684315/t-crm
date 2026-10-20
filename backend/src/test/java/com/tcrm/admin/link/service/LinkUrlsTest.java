package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.kol.api.KolRefDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkUrlsTest {
  private static final String TEMPLATE_WITH_INVITE =
      "https://item.kakobuy.com/item/details?url=x&affcode={INVITE_CODE}&item={PRODUCT_ID}";
  private static final String TEMPLATE_WITHOUT_INVITE =
      "https://cnfans.com/product/?shop_type=weidian&id={PRODUCT_ID}";

  /** KOL with an enabled template and an invite code. */
  private static KolRefDTO kol(String template, boolean enabled, String inviteCode) {
    return new KolRefDTO(10L, "KOL0001", "Alice", true, null, inviteCode, template, enabled);
  }

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
  void shouldFillTemplateWithInviteCodeAndItem_whenTemplateEnabledAndCodePresent() {
    assertThat(LinkUrls.targetUrl(kol(TEMPLATE_WITH_INVITE, true, "aff-01"), "9001"))
        .isEqualTo("https://item.kakobuy.com/item/details?url=x&affcode=aff-01&item=9001");
  }

  @Test
  void shouldFillTemplateWithoutInviteVariable_evenWhenKolHasNoCode() {
    assertThat(LinkUrls.targetUrl(kol(TEMPLATE_WITHOUT_INVITE, true, null), "9001"))
        .isEqualTo("https://cnfans.com/product/?shop_type=weidian&id=9001");
  }

  @Test
  void shouldFallBackToWeidian_whenTemplateNeedsCodeButKolHasNone() {
    assertThat(LinkUrls.targetUrl(kol(TEMPLATE_WITH_INVITE, true, null), "9001"))
        .isEqualTo("https://weidian.com/item.html?itemID=9001");
  }

  @Test
  void shouldFallBackToWeidian_whenTemplateIsDisabled() {
    assertThat(LinkUrls.targetUrl(kol(TEMPLATE_WITH_INVITE, false, "aff-01"), "9001"))
        .isEqualTo("https://weidian.com/item.html?itemID=9001");
  }

  @Test
  void shouldFallBackToWeidian_whenKolHasNoTemplate() {
    assertThat(LinkUrls.targetUrl(kol(null, false, "aff-01"), "9001"))
        .isEqualTo("https://weidian.com/item.html?itemID=9001");
  }

  @Test
  void shouldPercentEncodeSpecialCharactersInInviteCode() {
    assertThat(LinkUrls.targetUrl(kol(TEMPLATE_WITH_INVITE, true, "a b&c=d"), "9001"))
        .isEqualTo("https://item.kakobuy.com/item/details?url=x&affcode=a+b%26c%3Dd&item=9001");
  }

  @Test
  void shouldRejectBaseUrlWithPath() {
    assertThatThrownBy(() -> new LinkUrls("https://example.com/go"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TCRM_LINK_BASE_URL");
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
