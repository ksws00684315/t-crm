package com.tcrm.admin.order.service;

import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSettlementFlowDO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure computation of an order's commission basis from its persisted Weidian settlement flows (task
 * 76, design §4.8 / §8.3, ADR-032 2026-10-28 revision). Fail-safe by design: any fact that does not
 * match the confirmed rules leaves the order unverified with a queryable reason code, so a wrong
 * unit or an unexpected flow type can never produce a wrong commission.
 *
 * <p>Confirmed rules (owner 2026-10-28): the basis is the signed sum of every INCOME_DETAIL flow of
 * the order (goods income, fees, profit share, refunds); freight is not deducted; a single-order
 * basis is split across item lines by each line's pay ratio with the rounding tail on the largest
 * line; pre-settle full refunds have a basis of 0.00; release follows the settlement time of the
 * goods-income flow only.
 */
public final class SettlementBasisCalculator {
  /** Query type of the regular income detail (partial refunds included). */
  public static final String QUERY_INCOME_DETAIL = "INCOME_DETAIL";

  /** Query type of pre-settle full-refund orders. */
  public static final String QUERY_PRE_SETTLE_REFUND = "PRE_SETTLE_REFUND";

  /**
   * Unit of the {@code amount} field of {@code shop.order.trade.detail}. Default is minor units
   * (fen) per the task 75 review note — UNVERIFIED, no live sample yet. Confirm at M0 checklist
   * step 11; if live samples prove yuan, change this constant only.
   */
  static final boolean AMOUNT_IN_MINOR_UNITS = true;

  /**
   * Known {@code tradeType} values, from the open-platform doc example (income / expense). The doc
   * itself is behind a login, so these follow the recorded example in the task 75 prompt; actual
   * values are pending M0 step 11. A value outside this set fails the whole order safely.
   */
  static final Set<String> KNOWN_TRADE_TYPES = Set.of("收入", "支出");

  /**
   * Known {@code tradeSubType} values from the open-platform doc example rows. Pending M0 step 11
   * like {@link #KNOWN_TRADE_TYPES}.
   */
  static final Set<String> KNOWN_TRADE_SUB_TYPES = Set.of("货款收入", "交易手续费", "分润", "退款");

  /** The subtype of the goods-income row; its tradeTime is the order's settlement time. */
  static final String TRADE_SUB_TYPE_GOODS_INCOME = "货款收入";

  /** No matched settlement flow at all. */
  public static final String REASON_NO_SETTLEMENT = "NO_SETTLEMENT";

  /** A flow type outside the known sets; the order stays unverified. */
  public static final String REASON_UNKNOWN_FLOW_TYPE = "UNKNOWN_FLOW_TYPE";

  /**
   * Not exactly one goods-income row, an unparsable amount, or the converted goods income differs
   * from {@code crm_order.pay_amount} (a wrong unit lands here, preventing a 100x commission).
   */
  public static final String REASON_INCOME_MISMATCH = "INCOME_MISMATCH";

  /** The order's lines belong to more than one KOL. */
  public static final String REASON_MULTIPLE_KOLS = "MULTIPLE_KOLS";

  /** A non-zero basis cannot be split because every line's pay amount is zero. */
  public static final String REASON_ALLOCATION_FAILED = "ALLOCATION_FAILED";

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  private SettlementBasisCalculator() {}

  /** Result of the basis check: {@code reason} null means the basis was computed. */
  public record BasisOutcome(
      String reason, Instant settlementTime, Map<Long, BigDecimal> lineBasis) {
    static BasisOutcome unverified(String reason) {
      return new BasisOutcome(reason, null, Map.of());
    }
  }

  /** Computes the per-line basis of one order; no database or clock access. */
  public static BasisOutcome compute(
      OrderDO order, List<OrderItemDO> items, List<OrderSettlementFlowDO> flows) {
    List<OrderSettlementFlowDO> incomeFlows = new ArrayList<>();
    boolean preSettleListed = false;
    for (OrderSettlementFlowDO flow : flows) {
      if (QUERY_INCOME_DETAIL.equals(flow.getQueryType())) {
        incomeFlows.add(flow);
      } else if (QUERY_PRE_SETTLE_REFUND.equals(flow.getQueryType())) {
        preSettleListed = true;
      }
    }
    if (incomeFlows.isEmpty() && !preSettleListed) {
      return BasisOutcome.unverified(REASON_NO_SETTLEMENT);
    }
    if (flows.stream().anyMatch(SettlementBasisCalculator::unknownType)) {
      return BasisOutcome.unverified(REASON_UNKNOWN_FLOW_TYPE);
    }
    if (incomeFlows.isEmpty()) {
      // Pre-settle full refund: the amount is determined to be zero, so no commission can arise.
      Map<Long, BigDecimal> zeros = new HashMap<>();
      for (OrderItemDO item : items) {
        zeros.put(item.getId(), ZERO);
      }
      return new BasisOutcome(null, null, zeros);
    }
    OrderSettlementFlowDO income = singleGoodsIncome(incomeFlows);
    BigDecimal orderBasis = signedIncomeTotal(incomeFlows);
    // A goods-income row without tradeTime has no determined settlement time: the order would
    // verify but never release (UNSETTLED forever, without a reason). Treat it as a mismatch so
    // it stays unverified with a queryable reason (review rework, task 76).
    if (income == null
        || income.getTradeTime() == null
        || orderBasis == null
        || !incomeMatchesPay(income, order)) {
      return BasisOutcome.unverified(REASON_INCOME_MISMATCH);
    }
    if (distinctKols(items).size() > 1) {
      return BasisOutcome.unverified(REASON_MULTIPLE_KOLS);
    }
    Map<Long, BigDecimal> lineBasis = allocate(orderBasis, items);
    if (lineBasis == null) {
      return BasisOutcome.unverified(REASON_ALLOCATION_FAILED);
    }
    return new BasisOutcome(null, income.getTradeTime(), lineBasis);
  }

  /** Converts a raw flow amount to CNY with two decimals and no precision loss. */
  static BigDecimal toCny(BigDecimal raw) {
    if (raw == null) {
      return null;
    }
    BigDecimal cny = AMOUNT_IN_MINOR_UNITS ? raw.movePointLeft(2) : raw;
    return cny.setScale(2, RoundingMode.HALF_UP);
  }

  private static boolean unknownType(OrderSettlementFlowDO flow) {
    return flow.getTradeType() == null
        || !KNOWN_TRADE_TYPES.contains(flow.getTradeType())
        || flow.getTradeSubType() == null
        || !KNOWN_TRADE_SUB_TYPES.contains(flow.getTradeSubType());
  }

  private static OrderSettlementFlowDO singleGoodsIncome(List<OrderSettlementFlowDO> flows) {
    OrderSettlementFlowDO found = null;
    for (OrderSettlementFlowDO flow : flows) {
      if (TRADE_SUB_TYPE_GOODS_INCOME.equals(flow.getTradeSubType())) {
        if (found != null) {
          return null;
        }
        found = flow;
      }
    }
    return found;
  }

  /** Signed sum of every flow's converted amount; null when any amount is missing or unparsable. */
  private static BigDecimal signedIncomeTotal(List<OrderSettlementFlowDO> flows) {
    BigDecimal total = ZERO;
    for (OrderSettlementFlowDO flow : flows) {
      BigDecimal cny = toCny(flow.getAmountRaw());
      if (cny == null) {
        return null;
      }
      total = total.add(cny);
    }
    return total;
  }

  private static boolean incomeMatchesPay(OrderSettlementFlowDO income, OrderDO order) {
    BigDecimal incomeCny = toCny(income.getAmountRaw());
    return incomeCny != null
        && order.getPayAmount() != null
        && incomeCny.compareTo(order.getPayAmount()) == 0;
  }

  private static Set<Long> distinctKols(List<OrderItemDO> items) {
    Set<Long> kols = new HashSet<>();
    for (OrderItemDO item : items) {
      if (item.getKolId() != null) {
        kols.add(item.getKolId());
      }
    }
    return kols;
  }

  /**
   * Splits the order basis across lines by pay ratio, two decimals each; the rounding tail lands on
   * the largest-pay line (ties: the smallest order item id), so line bases always add up to the
   * order basis. Returns null when a non-zero basis meets an all-zero line-pay order.
   */
  private static Map<Long, BigDecimal> allocate(BigDecimal orderBasis, List<OrderItemDO> items) {
    Map<Long, BigDecimal> result = new LinkedHashMap<>();
    BigDecimal totalPay = BigDecimal.ZERO;
    for (OrderItemDO item : items) {
      BigDecimal pay = item.getPayAmount() == null ? BigDecimal.ZERO : item.getPayAmount();
      totalPay = totalPay.add(pay);
    }
    if (totalPay.signum() == 0) {
      if (orderBasis.compareTo(ZERO) == 0) {
        for (OrderItemDO item : items) {
          result.put(item.getId(), ZERO);
        }
        return result;
      }
      return null;
    }
    OrderItemDO tailLine = null;
    BigDecimal tailLinePay = null;
    BigDecimal allocated = ZERO;
    for (OrderItemDO item : items) {
      BigDecimal pay = item.getPayAmount() == null ? BigDecimal.ZERO : item.getPayAmount();
      BigDecimal basis = orderBasis.multiply(pay).divide(totalPay, 2, RoundingMode.HALF_UP);
      result.put(item.getId(), basis);
      allocated = allocated.add(basis);
      // Largest pay wins the tail; equal pays take the smaller item id.
      if (tailLine == null
          || pay.compareTo(tailLinePay) > 0
          || pay.compareTo(tailLinePay) == 0 && item.getId() < tailLine.getId()) {
        tailLine = item;
        tailLinePay = pay;
      }
    }
    BigDecimal tail = orderBasis.subtract(allocated);
    if (tail.signum() != 0) {
      result.put(tailLine.getId(), result.get(tailLine.getId()).add(tail));
    }
    return result;
  }
}
