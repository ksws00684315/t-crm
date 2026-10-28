package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import com.tcrm.admin.order.service.SettlementBasisCalculator.BasisOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the settlement basis check (task 76 §3.3, confirmed rules of 2026-10-28). All
 * hand-computations assume the default unit constant (fen) — the task 75 review note's best guess,
 * pending M0 step 11.
 */
class SettlementBasisCalculatorTest {
  private static final Instant INCOME_TIME = Instant.parse("2026-10-20T08:00:00Z");
  private static final long SHOP = 30L;
  private static final String ORDER = "WD-76-1";

  @Test
  void shouldConvertFenToCnyExactly() {
    // 600 fen -> 6.00; -4 fen -> -0.04; no precision loss, always two decimals.
    assertThat(SettlementBasisCalculator.toCny(new BigDecimal("600"))).isEqualByComparingTo("6.00");
    assertThat(SettlementBasisCalculator.toCny(new BigDecimal("-4"))).isEqualByComparingTo("-0.04");
    assertThat(SettlementBasisCalculator.toCny(new BigDecimal("12345")))
        .isEqualByComparingTo("123.45");
    assertThat(SettlementBasisCalculator.toCny(null)).isNull();
  }

  @Test
  void shouldReturnNoSettlementWhenNoFlowMatches() {
    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), List.of());

    assertThat(outcome.reason()).isEqualTo("NO_SETTLEMENT");
    assertThat(outcome.lineBasis()).isEmpty();
  }

  @Test
  void shouldReturnUnknownFlowTypeOutsideKnownSets() {
    // A tradeType/tradeSubType pair the doc example never mentions blocks the whole order.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            flow("支出", "平台服务费", "-4", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    assertThat(SettlementBasisCalculator.compute(order("6.00"), items(1), flows).reason())
        .isEqualTo("UNKNOWN_FLOW_TYPE");
    // A missing type value is equally unknown.
    assertThat(
            SettlementBasisCalculator.compute(
                    order("6.00"),
                    items(1),
                    List.of(
                        flow(
                            null,
                            "交易手续费",
                            "-4",
                            INCOME_TIME,
                            SettlementBasisCalculator.QUERY_INCOME_DETAIL)))
                .reason())
        .isEqualTo("UNKNOWN_FLOW_TYPE");
    assertThat(
            SettlementBasisCalculator.compute(
                    order("6.00"),
                    items(1),
                    List.of(
                        flow(
                            "支出",
                            null,
                            "-4",
                            INCOME_TIME,
                            SettlementBasisCalculator.QUERY_INCOME_DETAIL)))
                .reason())
        .isEqualTo("UNKNOWN_FLOW_TYPE");
  }

  @Test
  void shouldReturnIncomeMismatchOnWrongUnitOrMissingIncome() {
    // Unit deliberately wrong: the API returned yuan, we assume fen. 6 yuan -> 0.06 != pay 6.00.
    List<OrderSettlementFlowDO> yuanFlows =
        List.of(
            flow("收入", "货款收入", "6", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
            flow(
                "支出",
                "交易手续费",
                "-0.04",
                INCOME_TIME,
                SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    assertThat(SettlementBasisCalculator.compute(order("6.00"), items(1), yuanFlows).reason())
        .isEqualTo("INCOME_MISMATCH");
    // No goods-income row at all.
    assertThat(
            SettlementBasisCalculator.compute(
                    order("6.00"),
                    items(1),
                    List.of(
                        flow(
                            "支出",
                            "交易手续费",
                            "-4",
                            INCOME_TIME,
                            SettlementBasisCalculator.QUERY_INCOME_DETAIL)))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
    // Two goods-income rows are ambiguous.
    assertThat(
            SettlementBasisCalculator.compute(
                    order("6.00"), items(1), List.of(income("600"), income("600")))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
    // An unparsable amount anywhere blocks the signed sum.
    OrderSettlementFlowDO broken =
        flow("支出", "交易手续费", null, INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL);
    assertThat(
            SettlementBasisCalculator.compute(
                    order("6.00"), items(1), List.of(income("600"), broken))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
  }

  @Test
  void shouldReturnMultipleKolsWhenOrderSpansTwoKols() {
    List<OrderSettlementFlowDO> flows = List.of(income("600"));

    assertThat(SettlementBasisCalculator.compute(order("6.00"), items(2), flows).reason())
        .isEqualTo("MULTIPLE_KOLS");
  }

  @Test
  void shouldReturnAllocationFailedWhenEveryLinePayIsZero() {
    OrderDO order = order("6.00");
    List<OrderItemDO> zeroPay = List.of(item(9001L, 101L, "0.00"), item(9002L, 101L, "0.00"));

    assertThat(SettlementBasisCalculator.compute(order, zeroPay, List.of(income("600"))).reason())
        .isEqualTo("ALLOCATION_FAILED");
  }

  @Test
  void shouldAllocateSignedSumAcrossTwoLinesWithTailOnLargestPay() {
    // Hand-computed: basis = 600 - 4 = 596 fen = 5.96. Lines pay 4.00 and 2.00 (total 6.00):
    // 5.96 x 4/6 = 3.9733.. -> 3.97; 5.96 x 2/6 = 1.98667 -> 1.99; allocated 5.96, tail 0.00.
    List<OrderSettlementFlowDO> flows = List.of(income("600"), fee("-4"));
    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("3.97");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("1.99");
    assertThat(outcome.lineBasis().get(9001L).add(outcome.lineBasis().get(9002L)))
        .isEqualByComparingTo("5.96");
  }

  @Test
  void shouldAllocateThreeLinesAndPutRoundingTailOnLargestPayLine() {
    // Hand-computed, pays 1.00 / 2.00 / 3.00 (total 6.00):
    // basis 10.00 -> 1.67 + 3.33 + 5.00 = 10.00, tail 0.00;
    // basis 0.05 -> 0.01 + 0.02 + 0.03 = 0.06, tail -0.01 lands on the largest pay line
    // (9003): 0.03 - 0.01 = 0.02, so the lines still add up to 0.05.
    List<OrderSettlementFlowDO> flows = List.of(income("1005"), fee("-5"));
    BasisOutcome ten = SettlementBasisCalculator.compute(order("10.05"), items3(), flows);
    assertThat(ten.lineBasis().get(9001L)).isEqualByComparingTo("1.67");
    assertThat(ten.lineBasis().get(9002L)).isEqualByComparingTo("3.33");
    assertThat(ten.lineBasis().get(9003L)).isEqualByComparingTo("5.00");

    BasisOutcome five =
        SettlementBasisCalculator.compute(order("0.05"), items3(), List.of(income("5")));
    assertThat(five.lineBasis().get(9001L)).isEqualByComparingTo("0.01");
    assertThat(five.lineBasis().get(9002L)).isEqualByComparingTo("0.02");
    assertThat(five.lineBasis().get(9003L)).isEqualByComparingTo("0.02");
    assertThat(five.lineBasis().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("0.05");
  }

  @Test
  void shouldPreferSmallestItemIdWhenTailCandidatesTieOnPay() {
    // Two lines with equal pay 3.00 each, basis 0.05: 0.025 -> 0.03 each, allocated 0.06,
    // tail -0.01 lands on the smaller item id (9001): 0.03 - 0.01 = 0.02.
    List<OrderItemDO> items = List.of(item(9002L, 101L, "3.00"), item(9001L, 101L, "3.00"));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("0.05"), items, List.of(income("5")));

    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("0.02");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("0.03");
  }

  @Test
  void shouldReduceBasisWhenPartialRefundFlowExists() {
    // Hand-computed: income 600, fee -4, partial refund -60 (all fen) -> basis 536 fen = 5.36.
    // The income row still matches pay 6.00, so the order verifies with the reduced basis.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            fee("-4"),
            flow("支出", "退款", "-60", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("3.57");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("1.79");
  }

  @Test
  void shouldGiveZeroBasisToPreSettleFullRefundOrders() {
    // Listed under PRE_SETTLE_REFUND only: the amount is determined to be zero, verified, and
    // without a settlement time the release basis stays UNSETTLED.
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow(
                "支出",
                "退款",
                "-600",
                INCOME_TIME,
                SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("0.00");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldPreferIncomeDetailWhenBothQueryTypesExist() {
    // A settled order whose full refund arrived after settlement: the signed sum already nets it
    // out, so the INCOME_DETAIL path governs rather than the pre-settle zero basis.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            fee("-4"),
            flow("支出", "退款", "-596", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
            flow(
                "支出",
                "退款",
                "-596",
                INCOME_TIME,
                SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("0.00");
  }

  private static OrderDO order(String payAmount) {
    OrderDO order = new OrderDO();
    order.setId(5001L);
    order.setShopId(SHOP);
    order.setWeidianOrderId(ORDER);
    order.setPayAmount(new BigDecimal(payAmount));
    return order;
  }

  /** One KOL, two lines paying 4.00 and 2.00. */
  private static List<OrderItemDO> items(int kolCount) {
    OrderItemDO first = item(9001L, 101L, "4.00");
    OrderItemDO second = item(9002L, kolCount > 1 ? 202L : 101L, "2.00");
    return List.of(first, second);
  }

  /** One KOL, three lines paying 1.00, 2.00 and 3.00. */
  private static List<OrderItemDO> items3() {
    return List.of(item(9001L, 101L, "1.00"), item(9002L, 101L, "2.00"), item(9003L, 101L, "3.00"));
  }

  private static OrderItemDO item(long id, Long kolId, String pay) {
    OrderItemDO item = new OrderItemDO();
    item.setId(id);
    item.setSubOrderId("SUB-" + id);
    item.setKolId(kolId);
    item.setAttributionType(kolId == null ? 0 : 1);
    item.setPayAmount(new BigDecimal(pay));
    return item;
  }

  private static OrderSettlementFlowDO income(String fen) {
    return flow("收入", "货款收入", fen, INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL);
  }

  private static OrderSettlementFlowDO fee(String fen) {
    return flow("支出", "交易手续费", fen, INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL);
  }

  private static OrderSettlementFlowDO flow(
      String tradeType, String subType, String amount, Instant tradeTime, String queryType) {
    OrderSettlementFlowDO flow = new OrderSettlementFlowDO();
    flow.setShopId(SHOP);
    flow.setWeidianOrderId(ORDER);
    flow.setTradeType(tradeType);
    flow.setTradeSubType(subType);
    flow.setAmountRaw(amount == null ? null : new BigDecimal(amount));
    flow.setTradeTime(tradeTime);
    flow.setQueryType(queryType);
    return flow;
  }
}
