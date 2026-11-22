package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The order-chain reconciliation reads the same posted RMB amounts as shop receivables. */
final class OrderChainReconciliation {
  private static final String GOODS = "GOODS_INCOME";
  private static final String REFUND = "REFUND_DEDUCTION";
  private static final String EXPENSE = "COMMISSION_EXPENSE";

  private OrderChainReconciliation() {}

  static OrderChainDTO.WeidianReconciliationDTO of(List<OrderSettlementFlowDO> rows) {
    var flows =
        rows.stream()
            .filter(row -> SettlementBasisCalculator.QUERY_INCOME_DETAIL.equals(row.getQueryType()))
            .map(OrderChainReconciliation::flow)
            .filter(Objects::nonNull)
            .sorted(
                Comparator.comparing(
                        OrderChainDTO.WeidianFlowDTO::tradeTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OrderChainDTO.WeidianFlowDTO::id))
            .toList();
    var goods = flows.stream().filter(flow -> GOODS.equals(flow.type())).toList();
    BigDecimal settlement =
        goods.size() != 1
            ? null
            : total(flows.stream().filter(flow -> !EXPENSE.equals(flow.type())).toList());
    Instant settlementAt = settlement == null ? null : goods.getFirst().settlementAt();
    return new OrderChainDTO.WeidianReconciliationDTO(settlement, settlementAt, flows);
  }

  private static OrderChainDTO.WeidianFlowDTO flow(OrderSettlementFlowDO row) {
    String subtype = row.getTradeSubType();
    if (subtype == null) {
      return null;
    }
    String type =
        switch (subtype) {
          case SettlementBasisCalculator.TRADE_SUB_TYPE_GOODS_INCOME -> GOODS;
          case SettlementBasisCalculator.TRADE_SUB_TYPE_REFUND -> REFUND;
          // Only a separately identified platform commission expense is evidence of payment.
          case "佣金支出" -> EXPENSE;
          default -> null;
        };
    if (type == null) {
      return null;
    }
    BigDecimal amount = row.getAmountCny();
    if (EXPENSE.equals(type) && amount != null && amount.signum() > 0) {
      // The wallet uses signed amounts. An inconsistent expense cannot confirm an outflow.
      amount = null;
    }
    return new OrderChainDTO.WeidianFlowDTO(
        row.getId(),
        type,
        amount,
        row.getTradeTime(),
        GOODS.equals(type) ? row.getTradeTime() : null,
        null);
  }

  private static BigDecimal total(List<OrderChainDTO.WeidianFlowDTO> flows) {
    if (flows.isEmpty() || flows.stream().anyMatch(flow -> flow.amount() == null)) {
      return null;
    }
    return flows.stream()
        .map(OrderChainDTO.WeidianFlowDTO::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
