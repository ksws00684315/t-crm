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
 * Pure computation of an order's commission basis (task 81, design §4.8 / §8.3, ADR-032 2026-10-29
 * revision). Owner decisions of 2026-10-29: the basis is the order's pay amount (freight included)
 * from payment on, split across item lines by pay ratio, so accrual starts when the order is paid.
 * The Weidian settlement only confirms the basis: the goods-income flow must match the pay amount,
 * and its settlement time then drives the release. Fees, profit share and refunds in the settlement
 * flows never enter the basis.
 *
 * <p>Unverified orders carry a queryable reason code: unpaid, multiple KOLs, allocation failure.
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
   * The subtype of the goods-income row; its amount confirms the basis and its tradeTime is the
   * order's settlement time. Pending M0 step 11 like {@link #AMOUNT_IN_MINOR_UNITS}.
   */
  static final String TRADE_SUB_TYPE_GOODS_INCOME = "货款收入";

  /** Not paid yet, or no matched settlement flow while paid: the basis accrues, unconfirmed. */
  public static final String REASON_NO_SETTLEMENT = "NO_SETTLEMENT";

  /**
   * Not exactly one goods-income row, a row without tradeTime, an unparsable amount, or the
   * converted goods income differs from {@code crm_order.pay_amount} (a wrong unit lands here,
   * preventing a 100x commission). The basis still accrues from the pay amount; release waits.
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
    if (order.getPayTime() == null) {
      return BasisOutcome.unverified(REASON_NO_SETTLEMENT);
    }
    if (distinctKols(items).size() > 1) {
      return BasisOutcome.unverified(REASON_MULTIPLE_KOLS);
    }
    List<OrderSettlementFlowDO> incomeFlows = new ArrayList<>();
    boolean preSettleListed = false;
    for (OrderSettlementFlowDO flow : flows) {
      if (QUERY_INCOME_DETAIL.equals(flow.getQueryType())) {
        incomeFlows.add(flow);
      } else if (QUERY_PRE_SETTLE_REFUND.equals(flow.getQueryType())) {
        preSettleListed = true;
      }
    }
    if (incomeFlows.isEmpty()) {
      if (preSettleListed) {
        // Pre-settle full refund: the basis is determined to be zero, so no commission can arise.
        Map<Long, BigDecimal> zeros = new HashMap<>();
        for (OrderItemDO item : items) {
          zeros.put(item.getId(), ZERO);
        }
        return new BasisOutcome(null, null, zeros);
      }
      return accrueUnconfirmed(order, items, REASON_NO_SETTLEMENT);
    }
    OrderSettlementFlowDO income = singleGoodsIncome(incomeFlows);
    // A goods-income row without tradeTime has no determined settlement time: the order would
    // verify but never release (UNSETTLED forever, without a reason). Keep it unconfirmed instead.
    if (income == null || income.getTradeTime() == null || !incomeMatchesPay(income, order)) {
      return accrueUnconfirmed(order, items, REASON_INCOME_MISMATCH);
    }
    Map<Long, BigDecimal> lineBasis = allocate(toCny(income.getAmountRaw()), items);
    if (lineBasis == null) {
      return BasisOutcome.unverified(REASON_ALLOCATION_FAILED);
    }
    return new BasisOutcome(null, income.getTradeTime(), lineBasis);
  }

  /**
   * Signed sum in CNY of the INCOME_DETAIL flows (the Weidian withdrawable amount, display only);
   * null when the order has none or an amount cannot be read.
   */
  static BigDecimal incomeDetailTotal(List<OrderSettlementFlowDO> flows) {
    BigDecimal total = null;
    for (OrderSettlementFlowDO flow : flows) {
      if (!QUERY_INCOME_DETAIL.equals(flow.getQueryType())) {
        continue;
      }
      BigDecimal cny = toCny(flow.getAmountRaw());
      if (cny == null) {
        return null;
      }
      total = total == null ? cny : total.add(cny);
    }
    return total;
  }

  /** Converts a raw flow amount to CNY with two decimals and no precision loss. */
  static BigDecimal toCny(BigDecimal raw) {
    if (raw == null) {
      return null;
    }
    BigDecimal cny = AMOUNT_IN_MINOR_UNITS ? raw.movePointLeft(2) : raw;
    return cny.setScale(2, RoundingMode.HALF_UP);
  }

  /** Accrues the pay amount without a confirmed settlement time; release stays blocked. */
  private static BasisOutcome accrueUnconfirmed(
      OrderDO order, List<OrderItemDO> items, String reason) {
    if (order.getPayAmount() == null) {
      return BasisOutcome.unverified(REASON_ALLOCATION_FAILED);
    }
    Map<Long, BigDecimal> lineBasis = allocate(order.getPayAmount(), items);
    if (lineBasis == null) {
      return BasisOutcome.unverified(REASON_ALLOCATION_FAILED);
    }
    return new BasisOutcome(reason, null, lineBasis);
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
