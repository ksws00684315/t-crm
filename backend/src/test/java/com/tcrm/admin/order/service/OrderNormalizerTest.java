package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.integration.api.WeidianBusinessApi.OrderDetailResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.OrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderNormalizerTest {
  static final Instant PAID = Instant.parse("2026-10-16T00:00:00Z");
  static final JsonMapper JSON = JsonMapper.builder().build();

  static OrderDetailResponse detail(String id, String status, Instant paid, List<OrderLine> lines) {
    return new OrderDetailResponse(
        id,
        status,
        "ignored",
        new BigDecimal("12.34"),
        new BigDecimal("10"),
        PAID.minusSeconds(20),
        paid,
        lines,
        JSON.readTree(
            "{\"buyer_info\":{\"buyer_id\":\"test-buyer\",\"name\":\"NEVER-STORE\",\"phone\":\"NEVER-STORE\",\"address\":\"NEVER-STORE\"},\"express_fee_num\":\"2.34\"}"));
  }

  static OrderLine line(String id, String raw) {
    return new OrderLine(
        id,
        "Test item",
        new BigDecimal("10"),
        new BigDecimal("10"),
        BigDecimal.ONE,
        raw == null ? null : JSON.readTree(raw));
  }

  @Test
  void shouldUseCurrentShopIdsAndHashOnlyBuyerId_whenNormalizing() {
    var normalizer = new OrderNormalizer("test-only-salt");
    var result =
        normalizer.normalize(
            1,
            detail(
                "100",
                "pay",
                PAID,
                List.of(
                    line(
                        "90",
                        "{\"cur_shop_item_id\":\"91\",\"cur_shop_sku_id\":\"9\",\"sku_id\":\"8\",\"sku_title\":\"Blue\",\"sub_order_id\":\"10\"}"))),
            PAID);
    assertThat(result.items().getFirst().getWeidianItemId()).isEqualTo("91");
    assertThat(result.items().getFirst().getSkuId()).isEqualTo("9");
    assertThat(result.items().getFirst().getSubOrderId()).isEqualTo("10");
    assertThat(result.items().getFirst().getSkuName()).isEqualTo("Blue");
    assertThat(result.order().getBuyerHash())
        .isEqualTo(OrderNormalizer.hash("test-only-salttest-buyer"));
    assertThat(result.order().getFreight()).isEqualByComparingTo("2.34");
    assertThat(JSON.writeValueAsString(result))
        .doesNotContain("NEVER-STORE", "test-buyer", "buyer_info", "\"raw\":");
  }

  @Test
  void shouldGenerateStableDistinctKeys_whenSubordersAreAbsent() {
    var normalizer = new OrderNormalizer("test");
    var data = detail("100", "pay", PAID, List.of(line("90", null), line("90", null)));
    var first = normalizer.normalize(1, data, PAID);
    var second = normalizer.normalize(1, data, PAID);
    assertThat(first.items().stream().map(i -> i.getSubOrderId()).toList())
        .containsExactlyElementsOf(second.items().stream().map(i -> i.getSubOrderId()).toList())
        .doesNotHaveDuplicates();
    assertThat(first.items().getFirst().getSkuId()).isEmpty();
  }

  @Test
  void shouldRejectMalformedAndOutOfRangeData_whenNormalizing() {
    var normalizer = new OrderNormalizer("test");
    assertThatThrownBy(() -> new OrderNormalizer(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> normalizer.normalize(1, detail("100", "pay", PAID, List.of()), PAID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderNormalizer.required(null, 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderNormalizer.required("a".repeat(33), 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderNormalizer.money(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderNormalizer.money(new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderNormalizer.money(new BigDecimal("1000000000000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(OrderNormalizer.money(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
    var duplicate =
        detail(
            "100",
            "pay",
            PAID,
            List.of(
                line("90", "{\"sub_order_id\":\"1\"}"), line("90", "{\"sub_order_id\":\"1\"}")));
    assertThatThrownBy(() -> normalizer.normalize(1, duplicate, PAID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldHandleMissingBuyerAndOptionalFields_whenRemoteDataIsMinimal() {
    var data =
        new OrderDetailResponse(
            "100",
            "pay",
            null,
            BigDecimal.TEN,
            BigDecimal.TEN,
            PAID,
            PAID,
            List.of(line("90", "{\"sku_id\":\"8\"}")),
            null);
    var result = new OrderNormalizer("test").normalize(1, data, null);
    assertThat(result.order().getBuyerHash()).isNull();
    assertThat(result.order().getFreight()).isEqualByComparingTo("0");
    assertThat(result.items().getFirst().getSkuId()).isEqualTo("8");
  }

  @Test
  void shouldRejectMissingOrNonPositiveQuantity_whenNormalizing() {
    for (BigDecimal quantity : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("1.5")}) {
      var line = new OrderLine("90", "Test", BigDecimal.ONE, BigDecimal.ONE, quantity, null);
      assertThatThrownBy(
              () ->
                  new OrderNormalizer("test")
                      .normalize(1, detail("100", "pay", PAID, List.of(line)), PAID))
          .isInstanceOf(RuntimeException.class);
    }
  }
}
