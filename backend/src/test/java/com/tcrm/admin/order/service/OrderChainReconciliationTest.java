package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderChainReconciliationTest {
  private static final Instant PAID_IN = Instant.parse("2026-11-21T09:00:00Z");
  private static final Instant REFUNDED = PAID_IN.plusSeconds(1200);

  @Test
  void usesNormalizedYuanAndRejectsAmbiguousGoodsIncome() {
    var income = row(1, "货款收入", "6.00", PAID_IN, "INCOME_DETAIL");
    assertThat(income.getAmountRaw()).isEqualByComparingTo("600");
    assertThat(OrderChainReconciliation.of(List.of(income)).settlementAmount())
        .isEqualByComparingTo("6.00");

    var extra = row(2, "货款收入", "2.00", REFUNDED, "INCOME_DETAIL");
    var duplicateIncome = OrderChainReconciliation.of(List.of(income, extra));
    assertThat(duplicateIncome.settlementAmount()).isNull();
    assertThat(duplicateIncome.settlementAt()).isNull();
    assertThat(duplicateIncome.flows()).hasSize(2);
  }

  @Test
  void reconcilesSignedPostedFlowsAndKeepsPlatformFeesOutsideCommissionExpense() {
    var result =
        OrderChainReconciliation.of(
            List.of(
                row(2, "退款", "-20.00", REFUNDED, "INCOME_DETAIL"),
                row(1, "货款收入", "150.00", PAID_IN, "INCOME_DETAIL"),
                row(3, "分润", "-5.00", PAID_IN, "INCOME_DETAIL"),
                row(4, "交易手续费", "-2.00", PAID_IN, "INCOME_DETAIL"),
                row(5, "佣金支出", "-26.00", REFUNDED, "INCOME_DETAIL"),
                row(6, "退款", "-150.00", null, "PRE_SETTLE_REFUND")));

    assertThat(result.settlementAmount()).isEqualByComparingTo("130.00");
    assertThat(result.settlementAt()).isEqualTo(PAID_IN);
    assertThat(result.flows())
        .extracting(OrderChainDTO.WeidianFlowDTO::id)
        .containsExactly(1L, 2L, 5L);
    assertThat(result.flows().get(1).amount()).isEqualByComparingTo("-20.00");
    assertThat(result.flows().get(1).settlementAt()).isNull();
    assertThat(result.flows().getLast().amount()).isEqualByComparingTo("-26.00");
    assertThat(result.flows()).allMatch(flow -> flow.settlementNo() == null);
  }

  @Test
  void leavesAmountsUnknownWithoutGoodsIncomeOrAnExplicitExpense() {
    var result =
        OrderChainReconciliation.of(
            List.of(
                row(1, "退款", "-20.00", REFUNDED, "INCOME_DETAIL"),
                row(2, "分润", "-5.00", PAID_IN, "INCOME_DETAIL")));

    assertThat(result.settlementAmount()).isNull();
    assertThat(result.settlementAt()).isNull();
    assertThat(result.flows()).hasSize(1);
  }

  @Test
  void leavesAffectedTotalsUnknownWhenOneOfTheirRowsHasNoAmount() {
    var result =
        OrderChainReconciliation.of(
            List.of(
                row(1, "货款收入", "150.00", PAID_IN, "INCOME_DETAIL"),
                row(2, "退款", null, REFUNDED, "INCOME_DETAIL"),
                row(3, "佣金支出", "26.00", REFUNDED, "INCOME_DETAIL")));

    assertThat(result.settlementAmount()).isNull();
    assertThat(result.settlementAt()).isNull();
    assertThat(result.flows().getLast().amount()).isNull();
    assertThat(result.flows()).hasSize(3);
  }

  private static OrderSettlementFlowDO row(
      long id, String subtype, String amount, Instant time, String queryType) {
    var row = new OrderSettlementFlowDO();
    row.setId(id);
    row.setTradeSubType(subtype);
    if (amount != null) {
      BigDecimal yuan = new BigDecimal(amount);
      row.setAmountRaw(yuan.movePointRight(2));
      row.setAmountCny(yuan);
    }
    row.setTradeTime(time);
    row.setQueryType(queryType);
    return row;
  }
}
