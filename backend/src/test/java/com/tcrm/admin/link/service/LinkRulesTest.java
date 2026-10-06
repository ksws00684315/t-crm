package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.link.model.LinkUnavailableReason;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LinkRulesTest {
  private static KolItemLinkDTO link(boolean onSale, boolean shopEnabled) {
    return new KolItemLinkDTO(
        1234567890123L, 10L, 20L, "P000123", "鞋", 1L, "9001", onSale, shopEnabled);
  }

  @ParameterizedTest
  @CsvSource({
    "true, true, true, 1",
    "false, true, true, 0",
    "true, false, true, 0",
    "true, true, false, 0",
    "false, false, false, 0"
  })
  void shouldBeActiveOnlyWhenKolProductAndShopAreUsable(
      boolean kolEnabled, boolean onSale, boolean shopEnabled, int expected) {
    assertThat(LinkRules.status(kolEnabled, link(onSale, shopEnabled))).isEqualTo(expected);
  }

  @Test
  void shouldListEveryReason_whenKolProductAndShopAreAllUnusable() {
    assertThat(LinkRules.reasons(false, link(false, false)))
        .containsExactly(
            LinkUnavailableReason.KOL_DISABLED,
            LinkUnavailableReason.PRODUCT_OFF_SHELF,
            LinkUnavailableReason.SHOP_DISABLED);
  }

  @Test
  void shouldListNoReason_whenLinkUsable() {
    assertThat(LinkRules.reasons(true, link(true, true))).isEmpty();
  }

  @Test
  void shouldWriteRedirectCompatibleJson_whenLinkActive() {
    JsonNode node =
        JsonMapper.builder().build().readTree(LinkRules.indexValue(true, link(true, true)));
    assertThat(node.size()).isEqualTo(3);
    assertThat(node.path("kolItemId").isString()).isTrue();
    assertThat(node.path("kolItemId").asString()).isEqualTo("1234567890123");
    assertThat(node.path("targetUrl").asString())
        .isEqualTo("https://weidian.com/item.html?itemID=9001");
    assertThat(node.path("status").isIntegralNumber()).isTrue();
    assertThat(node.path("status").asInt()).isEqualTo(1);
  }

  @Test
  void shouldWriteStatusZero_whenProductOffShelf() {
    JsonNode node =
        JsonMapper.builder().build().readTree(LinkRules.indexValue(true, link(false, true)));
    assertThat(node.path("status").asInt()).isZero();
  }
}
