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
 * Unit tests of the settlement basis check (task 81, design §4.8 / §8.3, owner decisions of
 * 2026-10-29): the basis is the pay amount from payment on and the goods-income flow only confirms
 * it. All hand-computations assume the default unit constant (fen), pending M0 step 11.
 */
class SettlementBasisCalculatorTest {
  private static final Instant PAY_TIME = Instant.parse("2026-10-10T08:00:00Z");
  private static final Instant INCOME_TIME = Instant.parse("2026-10-20T08:00:00Z");
  private static final long SHOP = 30L;
  private static final String ORDER = "WD-81-1";

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
  void shouldAccrueFromPayAmountBeforeSettlement() {
    // Simulated order (2026-10-29 record): pay 65.00 = goods A 40.00 + goods B 20.00 + freight.
    // 65.00 x 40/60 = 43.33; 65.00 x 20/60 = 21.67; no tail. Paid, no settlement flow yet.
    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("65.00"), simulatedLines(), List.of());

    assertThat(outcome.reason()).isEqualTo("NO_SETTLEMENT");
    assertThat(outcome.settlementTime()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("43.33");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("21.67");
  }

  @Test
  void shouldNotAccrueUnpaidOrder() {
    OrderDO unpaid = order("65.00");
    unpaid.setPayTime(null);

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(unpaid, simulatedLines(), List.of(income("6500")));

    assertThat(outcome.reason()).isEqualTo("NO_SETTLEMENT");
    assertThat(outcome.lineBasis()).isEmpty();
  }

  @Test
  void shouldConfirmWithGoodsIncomeAndReleaseAtItsTradeTime() {
    // Goods income 6500 fen = 65.00 matches pay: settled, release time = its tradeTime.
    BasisOutcome outcome =
        SettlementBasisCalculator.compute(
            order("65.00"), simulatedLines(), List.of(income("6500")));

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("43.33");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("21.67");
  }

  @Test
  void shouldIgnoreFeesProfitShareRefundsAndUnknownSubtypesWhenConfirming() {
    // Fee -20, profit share -10, a refund flow and a never-seen subtype: none of them enters the
    // basis or blocks confirmation. The basis stays the goods income 65.00.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("6500"),
            flow("支出", "交易手续费", "-20", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
            flow("支出", "分润", "-10", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
            flow("支出", "退款", "-600", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL),
            flow("其他", "未知", "7", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("65.00"), simulatedLines(), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("43.33");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("21.67");
  }

  @Test
  void shouldKeepAccrualButNotConfirmWhenGoodsIncomeMismatches() {
    // Wrong unit: the API returned yuan, we read fen. 65 yuan -> 0.65 != pay 65.00. The accrual
    // from the pay amount stays, release waits, and the reason is queryable.
    BasisOutcome wrongUnit =
        SettlementBasisCalculator.compute(order("65.00"), simulatedLines(), List.of(income("65")));
    assertThat(wrongUnit.reason()).isEqualTo("INCOME_MISMATCH");
    assertThat(wrongUnit.settlementTime()).isNull();
    assertThat(wrongUnit.lineBasis().get(9001L)).isEqualByComparingTo("43.33");
    assertThat(wrongUnit.lineBasis().get(9002L)).isEqualByComparingTo("21.67");
  }

  @Test
  void shouldReturnIncomeMismatchOnMissingOrAmbiguousGoodsIncome() {
    // Goods-income row without tradeTime: no determined settlement time, so it is not confirmed.
    assertThat(
            SettlementBasisCalculator.compute(
                    order("65.00"),
                    simulatedLines(),
                    List.of(
                        flow(
                            "收入",
                            "货款收入",
                            "6500",
                            null,
                            SettlementBasisCalculator.QUERY_INCOME_DETAIL)))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
    // No goods-income row at all, only a fee.
    assertThat(
            SettlementBasisCalculator.compute(order("65.00"), simulatedLines(), List.of(fee("-20")))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
    // Two goods-income rows are ambiguous.
    assertThat(
            SettlementBasisCalculator.compute(
                    order("65.00"), simulatedLines(), List.of(income("6500"), income("6500")))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
    // An unparsable goods-income amount blocks confirmation.
    OrderSettlementFlowDO broken =
        flow("收入", "货款收入", null, INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL);
    assertThat(
            SettlementBasisCalculator.compute(order("65.00"), simulatedLines(), List.of(broken))
                .reason())
        .isEqualTo("INCOME_MISMATCH");
  }

  @Test
  void shouldReturnMultipleKolsWhenOrderSpansTwoKols() {
    List<OrderItemDO> lines = List.of(item(9001L, 101L, "4.00"), item(9002L, 202L, "2.00"));

    assertThat(
            SettlementBasisCalculator.compute(order("6.00"), lines, List.of(income("600")))
                .reason())
        .isEqualTo("MULTIPLE_KOLS");
    assertThat(SettlementBasisCalculator.compute(order("6.00"), lines, List.of()).reason())
        .isEqualTo("MULTIPLE_KOLS");
  }

  @Test
  void shouldReturnAllocationFailedWhenEveryLinePayIsZero() {
    List<OrderItemDO> zeroPay = List.of(item(9001L, 101L, "0.00"), item(9002L, 101L, "0.00"));

    assertThat(
            SettlementBasisCalculator.compute(order("6.00"), zeroPay, List.of(income("600")))
                .reason())
        .isEqualTo("ALLOCATION_FAILED");
    assertThat(SettlementBasisCalculator.compute(order("6.00"), zeroPay, List.of()).reason())
        .isEqualTo("ALLOCATION_FAILED");
  }

  @Test
  void shouldAllocateGoodsIncomeAcrossTwoLinesWithTailOnLargestPay() {
    // Pay 4.00 and 2.00 (total 6.00), goods income 6.00 -> 4.00 and 2.00 exactly. The fee of -0.04
    // does not reduce the basis (task 81).
    List<OrderSettlementFlowDO> flows = List.of(income("600"), fee("-4"));
    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("4.00");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("2.00");
  }

  @Test
  void shouldAllocateThreeLinesAndPutRoundingTailOnLargestPayLine() {
    // Hand-computed, pays 1.00 / 2.00 / 3.00 (total 6.00):
    // basis 10.00 -> 1.67 + 3.33 + 5.00 = 10.00, tail 0.00;
    // basis 0.05 -> 0.01 + 0.02 + 0.03 = 0.06, tail -0.01 lands on the largest pay line
    // (9003): 0.03 - 0.01 = 0.02, so the lines still add up to 0.05.
    List<OrderSettlementFlowDO> flows = List.of(income("1000"), fee("-5"));
    BasisOutcome ten = SettlementBasisCalculator.compute(order("10.00"), items3(), flows);
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
  void shouldNetSettlementRefundGapByPayRatioWhenCrmHasNoRecord() {
    // Refund -0.60 sits in INCOME_DETAIL with no CRM refund record: the gap 0.60 is netted by pay
    // ratio (4 : 2), so the lines carry 0.40 and 0.20 extra refund. The basis stays the pay amount.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            fee("-4"),
            flow("支出", "退款", "-60", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("4.00");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("2.00");
    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("0.40");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("0.20");
    assertThat(outcome.refundGap()).isTrue();
  }

  @Test
  void shouldCountRefundOnceWhenCrmRecordAndFlowAgree() {
    // The same 0.60 refund is in the CRM records (line 9001) and in the flows: netted once.
    List<OrderItemDO> lines = items(1);
    lines.get(0).setRefundAmount(new BigDecimal("0.60"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            flow("支出", "退款", "-60", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), lines, flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.lineExtraRefund()).isEmpty();
    assertThat(outcome.refundGap()).isFalse();
  }

  @Test
  void shouldNetOnlyTheExcessWhenFlowRefundExceedsCrmRecord() {
    // CRM records 0.20 on line 9002; the flows show 0.60. The excess 0.40 is netted by pay ratio:
    // 0.40 x 4/6 = 0.27 and 0.40 x 2/6 = 0.13, so the total refund is 0.60 either way.
    List<OrderItemDO> lines = items(1);
    lines.get(1).setRefundAmount(new BigDecimal("0.20"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            flow("支出", "退款", "-60", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), lines, flows);

    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("0.27");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("0.13");
    assertThat(outcome.refundGap()).isTrue();
  }

  @Test
  void shouldMoveTruncatedGapToLinesWithRemainingBasis() {
    // L-035. Three lines pay 40.00 / 30.00 / 30.00 (order pay 100.00). Line 9001 is already fully
    // refunded in the CRM (40.00, C = 40.00) and the flows show 52.00, so the gap is 12.00. The
    // pay-ratio first pass would give 9001 4.80, but its basis is used up; the truncated 4.80 goes
    // to the lines that still have basis (2.40 each). The total stays exactly F - C = 12.00.
    List<OrderItemDO> lines =
        List.of(item(9001L, 101L, "40.00"), item(9002L, 101L, "30.00"), item(9003L, 101L, "30.00"));
    lines.get(0).setRefundAmount(new BigDecimal("40.00"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-5200", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("100.00"), lines, flows);

    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("0.00");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("6.00");
    assertThat(outcome.lineExtraRefund().get(9003L)).isEqualByComparingTo("6.00");
    assertThat(extraTotal(outcome)).isEqualByComparingTo("12.00");
    assertThat(outcome.refundGapUnallocated()).isEqualByComparingTo("0.00");
    assertThat(outcome.refundGap()).isTrue();
  }

  @Test
  void shouldRedistributeAcrossRoundsWhenSeveralLinesRunOut() {
    // Lines pay 10.00 / 10.00 / 80.00 (order pay 100.00); CRM refunded 10.00 on 9001 and 9.50 on
    // 9002 (C = 19.50), the flows show 59.50, so the gap is 40.00. First pass by pay ratio: 4.00,
    // 4.00, 32.00. 9001 has 0.00 left, 9002 has 0.50 left (truncated 3.50), 9003 absorbs the rest:
    // 9001 0.00, 9002 0.50, 9003 39.50. The tail line is the only one with room.
    List<OrderItemDO> lines =
        List.of(item(9001L, 101L, "10.00"), item(9002L, 101L, "10.00"), item(9003L, 101L, "80.00"));
    lines.get(0).setRefundAmount(new BigDecimal("10.00"));
    lines.get(1).setRefundAmount(new BigDecimal("9.50"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-5950", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("100.00"), lines, flows);

    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("0.00");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("0.50");
    assertThat(outcome.lineExtraRefund().get(9003L)).isEqualByComparingTo("39.50");
    assertThat(extraTotal(outcome)).isEqualByComparingTo("40.00");
    assertThat(outcome.refundGapUnallocated()).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldKeepEveryLineBasisNonNegativeAndRecordWhatNoLineCanTake() {
    // Lines pay 4.00 / 2.00 (order pay 6.00); the CRM refunded 1.00 on 9001. The flows show 10.00,
    // so the gap is 9.00 but only 3.00 + 2.00 = 5.00 of basis is left. Every line is netted to
    // exactly zero (never negative), and the 4.00 nobody can absorb is reported, not invented.
    List<OrderItemDO> lines = items(1);
    lines.get(0).setRefundAmount(new BigDecimal("1.00"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-1000", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), lines, flows);

    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("3.00");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("2.00");
    assertThat(outcome.refundGapUnallocated()).isEqualByComparingTo("4.00");
    // basis - CRM refund - extra == 0 on every line, so the commission engine floors nothing.
    assertThat(
            outcome
                .lineBasis()
                .get(9001L)
                .subtract(lines.get(0).getRefundAmount())
                .subtract(outcome.lineExtraRefund().get(9001L)))
        .isEqualByComparingTo("0.00");
    assertThat(
            outcome
                .lineBasis()
                .get(9002L)
                .subtract(BigDecimal.ZERO)
                .subtract(outcome.lineExtraRefund().get(9002L)))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void shouldRoundTheRedistributionToTheCentWithTheTailOnTheLargestPayLine() {
    // Lines pay 1.00 / 2.00 / 3.00 (order pay 6.00); 9003 is fully refunded in the CRM (3.00).
    // The flows show 3.10, so the gap is 0.10. The first pass gives 0.02 / 0.03 / 0.05 (tail on the
    // largest pay); 9003 has no room, so 0.05 moves to 9001 and 9002 by pay ratio 1 : 2 -> 0.02 +
    // 0.03 (the tail goes to the larger line). Total exactly 0.10.
    List<OrderItemDO> lines = items3();
    lines.get(2).setRefundAmount(new BigDecimal("3.00"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-310", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), lines, flows);

    assertThat(outcome.lineExtraRefund().get(9003L)).isEqualByComparingTo("0.00");
    assertThat(extraTotal(outcome)).isEqualByComparingTo("0.10");
    assertThat(outcome.lineExtraRefund().get(9001L)).isEqualByComparingTo("0.04");
    assertThat(outcome.lineExtraRefund().get(9002L)).isEqualByComparingTo("0.06");
  }

  @Test
  void shouldKeepAllocationFailedWhenTheGapMeetsAnOrderWithoutLinePay() {
    // Existing rule kept: a non-zero gap cannot be split when every line pay is zero.
    OrderItemDO zeroA = item(9001L, 101L, "0.00");
    OrderItemDO zeroB = item(9002L, 101L, "0.00");
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-100", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("0.00"), List.of(zeroA, zeroB), flows);

    assertThat(outcome.reason()).isEqualTo("ALLOCATION_FAILED");
  }

  @Test
  void shouldGiveIdenticalOutcomesOnRepeatedComputation() {
    // The commission fingerprint is built from these numbers: recomputing the same facts must give
    // the same lines in the same order (task 83: no new revision for an unchanged order).
    List<OrderItemDO> lines =
        List.of(item(9001L, 101L, "40.00"), item(9002L, 101L, "30.00"), item(9003L, 101L, "30.00"));
    lines.get(0).setRefundAmount(new BigDecimal("40.00"));
    List<OrderSettlementFlowDO> flows =
        List.of(
            flow("支出", "退款", "-5200", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    BasisOutcome first = SettlementBasisCalculator.compute(order("100.00"), lines, flows);
    BasisOutcome second =
        SettlementBasisCalculator.compute(order("100.00"), List.copyOf(lines), flows);

    assertThat(second.lineExtraRefund().toString()).isEqualTo(first.lineExtraRefund().toString());
    assertThat(second.lineBasis().toString()).isEqualTo(first.lineBasis().toString());
    assertThat(second.refundGapUnallocated()).isEqualTo(first.refundGapUnallocated());
  }

  @Test
  void shouldNotNetFlowsWhenCrmRefundsExceedFlows() {
    // The CRM record is ahead of the flows (flow refund not yet arrived): the CRM refund alone
    // stands, nothing extra is netted and no gap is flagged.
    List<OrderItemDO> lines = items(1);
    lines.get(0).setRefundAmount(new BigDecimal("0.60"));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("6.00"), lines, List.of(income("600")));

    assertThat(outcome.lineExtraRefund()).isEmpty();
    assertThat(outcome.refundGap()).isFalse();
  }

  @Test
  void shouldGiveZeroBasisToOrderClosedAfterPayment() {
    // Closed after payment means the whole order was refunded: the basis is zero even though a
    // goods income row exists, and no settlement time is set.
    OrderDO closed = order("6.00");
    closed.setOrderStatus("CLOSED");

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(closed, items(1), List.of(income("600")));

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("0.00");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("0.00");
  }

  @Test
  void shouldConfirmWhenGoodsIncomeIsPayAmountLessCrmRefund() {
    // A refund before settlement leaves the goods income at pay minus refund (5.40 = 6.00 - 0.60).
    List<OrderItemDO> lines = items(1);
    lines.get(0).setRefundAmount(new BigDecimal("0.60"));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("6.00"), lines, List.of(income("540")));

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("4.00");
    assertThat(outcome.lineBasis().get(9002L)).isEqualByComparingTo("2.00");
  }

  @Test
  void shouldReturnIncomeMismatchWhenGoodsIncomeIsNeitherPayNorNetRefund() {
    // 5.00 matches neither 6.00 nor 5.40: unconfirmed, the basis still accrues from the pay amount.
    List<OrderItemDO> lines = items(1);
    lines.get(0).setRefundAmount(new BigDecimal("0.60"));

    BasisOutcome outcome =
        SettlementBasisCalculator.compute(order("6.00"), lines, List.of(income("500")));

    assertThat(outcome.reason()).isEqualTo("INCOME_MISMATCH");
    assertThat(outcome.settlementTime()).isNull();
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("4.00");
  }

  @Test
  void shouldGiveZeroBasisToPreSettleFullRefundOrders() {
    // Listed under PRE_SETTLE_REFUND only: the basis is determined to be zero, verified, and
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
  void shouldPreferConfirmedIncomeWhenBothQueryTypesExist() {
    // A settled order that also lists a pre-settle full refund: the goods income confirms the
    // basis, so the pre-settle zero path does not apply.
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("600"),
            flow(
                "支出",
                "退款",
                "-600",
                INCOME_TIME,
                SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND));

    BasisOutcome outcome = SettlementBasisCalculator.compute(order("6.00"), items(1), flows);

    assertThat(outcome.reason()).isNull();
    assertThat(outcome.settlementTime()).isEqualTo(INCOME_TIME);
    assertThat(outcome.lineBasis().get(9001L)).isEqualByComparingTo("4.00");
  }

  @Test
  void shouldSumIncomeDetailFlowsAsWithdrawableAmount() {
    // Display-only total: 65.00 - 0.20 - 0.10 = 64.70 (the 2026-10-29 simulated order).
    List<OrderSettlementFlowDO> flows =
        List.of(
            income("6500"),
            fee("-20"),
            flow("支出", "分润", "-10", INCOME_TIME, SettlementBasisCalculator.QUERY_INCOME_DETAIL));

    assertThat(SettlementBasisCalculator.incomeDetailTotal(flows)).isEqualByComparingTo("64.70");
  }

  @Test
  void shouldReturnNullWithdrawableWithoutIncomeDetailOrReadableAmount() {
    assertThat(SettlementBasisCalculator.incomeDetailTotal(List.of())).isNull();
    assertThat(
            SettlementBasisCalculator.incomeDetailTotal(
                List.of(
                    flow(
                        "支出",
                        "退款",
                        "-600",
                        INCOME_TIME,
                        SettlementBasisCalculator.QUERY_PRE_SETTLE_REFUND))))
        .isNull();
    assertThat(
            SettlementBasisCalculator.incomeDetailTotal(
                List.of(
                    flow(
                        "支出",
                        "交易手续费",
                        null,
                        INCOME_TIME,
                        SettlementBasisCalculator.QUERY_INCOME_DETAIL))))
        .isNull();
  }

  private static BigDecimal extraTotal(BasisOutcome outcome) {
    return outcome.lineExtraRefund().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static OrderDO order(String payAmount) {
    OrderDO order = new OrderDO();
    order.setId(5001L);
    order.setShopId(SHOP);
    order.setWeidianOrderId(ORDER);
    order.setPayTime(PAY_TIME);
    order.setPayAmount(new BigDecimal(payAmount));
    return order;
  }

  /** One KOL, two lines paying 4.00 and 2.00. */
  private static List<OrderItemDO> items(int kolCount) {
    OrderItemDO first = item(9001L, 101L, "4.00");
    OrderItemDO second = item(9002L, kolCount > 1 ? 202L : 101L, "2.00");
    return List.of(first, second);
  }

  /** Goods A 40.00 and goods B 20.00 of the simulated order, one KOL. */
  private static List<OrderItemDO> simulatedLines() {
    return List.of(item(9001L, 101L, "40.00"), item(9002L, 101L, "20.00"));
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
