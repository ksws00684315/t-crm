package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderChainCommissionApi.OrderCommissionChainDTO;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Derived funds status of the chain. The former derived flow events (SR/SRF/CA/CR) were replaced by
 * the subject postings of {@code crm_finance_flow} (task 159, task 158 §5.2); only the funds block
 * status and the refund-netted amount remain here.
 */
record OrderChainAccrual(String status, BigDecimal amount) {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
  private static final Comparator<Instant> EVENT_TIME =
      Comparator.nullsLast(Comparator.naturalOrder());

  static OrderChainAccrual of(
      OrderDO order,
      List<OrderItemDO> items,
      List<RefundDO> refunds,
      OrderCommissionChainDTO commission) {
    if (order.getPayTime() == null || items.isEmpty()) {
      return new OrderChainAccrual(null, null);
    }

    var effectiveRefunds =
        refunds.stream()
            .filter(r -> "SUCCESS".equals(r.getRefundStatus()) || pending(r))
            .filter(r -> r.getId() != null)
            .sorted(
                Comparator.comparing(OrderChainAccrual::pending)
                    .thenComparing(OrderChainAccrual::eventTime, EVENT_TIME)
                    .thenComparing(RefundDO::getId))
            .toList();
    boolean hasPending = effectiveRefunds.stream().anyMatch(OrderChainAccrual::pending);

    var rates =
        commission.lines().stream()
            .filter(line -> line.rateSnapshot() != null)
            .collect(Collectors.toMap(line -> line.orderItemId(), line -> line.rateSnapshot()));
    Map<Long, BigDecimal> basis =
        order.getPayAmount() != null && commission.allVerified() && rates.size() == items.size()
            ? SettlementBasisCalculator.allocate(order.getPayAmount(), items)
            : null;
    BigDecimal gross =
        effectiveRefunds.isEmpty()
            ? commission.allVerified() ? commission.netCommissionCny() : null
            : basis != null ? amount(basis, rates) : null;
    if (commission.afterSales() && effectiveRefunds.isEmpty()) {
      return new OrderChainAccrual("UNVERIFIED", null);
    }

    BigDecimal remaining = gross;
    var seen = new HashSet<Long>();
    for (RefundDO refund : effectiveRefunds) {
      if (!seen.add(refund.getId())) {
        continue;
      }
      if (remaining != null && basis != null && applyRefund(basis, rates, refund)) {
        remaining = amount(basis, rates);
      } else {
        remaining = null;
      }
    }
    if (!effectiveRefunds.isEmpty()) {
      return new OrderChainAccrual(hasPending ? "REFUNDING" : "REFUNDED", remaining);
    }
    if (gross == null) {
      return new OrderChainAccrual("UNVERIFIED", null);
    }
    BigDecimal released = releasedCommission(commission);
    boolean frozen = released.compareTo(gross) < 0 || released.signum() == 0;
    return new OrderChainAccrual(frozen ? "FROZEN" : "POSTED", frozen ? ZERO : released);
  }

  String commissionStatus(OrderCommissionChainDTO commission) {
    if (commission.lines().stream().anyMatch(line -> line.occupiedCny().signum() > 0)) {
      return "WITHDRAWN";
    }
    if ("REFUNDING".equals(status)) {
      return amount == null ? "UNVERIFIED" : "FROZEN";
    }
    if ("REFUNDED".equals(status)) {
      BigDecimal remaining =
          commission.allVerified() && !commission.afterSales()
              ? commission.netCommissionCny()
              : amount;
      if (remaining == null) {
        return "UNVERIFIED";
      }
      if (remaining.signum() == 0) {
        return "INVALID";
      }
      return releasedCommission(commission).compareTo(remaining) < 0 ? "FROZEN" : "WITHDRAWABLE";
    }
    return "POSTED".equals(status) ? "WITHDRAWABLE" : status;
  }

  private static BigDecimal releasedCommission(OrderCommissionChainDTO commission) {
    return commission.flows().stream()
        .filter(flow -> flow.withdrawalNo() == null && flow.flowNo().startsWith("CL"))
        .map(
            flow ->
                com.tcrm.admin.order.api.OrderChainCommissionApi.DIRECTION_DECREASE.equals(
                        flow.direction())
                    ? flow.amountCny().negate()
                    : flow.amountCny())
        .reduce(ZERO, BigDecimal::add)
        .max(ZERO);
  }

  private static boolean applyRefund(
      Map<Long, BigDecimal> basis, Map<Long, BigDecimal> rates, RefundDO refund) {
    BigDecimal refundAmount = refund.getAmount();
    if (refundAmount == null || refundAmount.signum() < 0) {
      return false;
    }
    Long itemId = refund.getOrderItemId();
    if (itemId != null) {
      BigDecimal current = basis.get(itemId);
      if (current == null) {
        return false;
      }
      basis.put(itemId, current.subtract(refundAmount).max(ZERO));
      return true;
    }
    BigDecimal total = basis.values().stream().reduce(ZERO, BigDecimal::add);
    if (refundAmount.compareTo(total) >= 0) {
      basis.replaceAll((id, value) -> ZERO);
      return true;
    }
    // A partial order-wide refund cannot be attributed to lines with different pay-time rates.
    var rateCount = rates.values().stream().map(BigDecimal::stripTrailingZeros).distinct().count();
    if (rateCount > 1) {
      return false;
    }
    // An order-wide adjustment with a single rate spreads by the remaining line basis.
    BigDecimal unassigned = refundAmount;
    var remaining = new LinkedHashMap<>(basis);
    for (Map.Entry<Long, BigDecimal> line : remaining.entrySet()) {
      BigDecimal part = refundAmount.multiply(line.getValue()).divide(total, 2, RoundingMode.DOWN);
      basis.put(line.getKey(), line.getValue().subtract(part));
      unassigned = unassigned.subtract(part);
    }
    for (Map.Entry<Long, BigDecimal> line : basis.entrySet()) {
      BigDecimal take = line.getValue().min(unassigned);
      line.setValue(line.getValue().subtract(take));
      unassigned = unassigned.subtract(take);
      if (unassigned.signum() == 0) {
        break;
      }
    }
    return true;
  }

  private static BigDecimal amount(Map<Long, BigDecimal> basis, Map<Long, BigDecimal> rates) {
    return basis.entrySet().stream()
        .map(
            line ->
                line.getValue()
                    .multiply(rates.get(line.getKey()))
                    .setScale(2, RoundingMode.HALF_UP))
        .reduce(ZERO, BigDecimal::add);
  }

  private static boolean pending(RefundDO refund) {
    return OrderCommissionSync.UNCONCLUDED_REFUND_STATUSES.contains(
        Objects.toString(refund.getRefundStatus(), ""));
  }

  private static Instant eventTime(RefundDO refund) {
    return !pending(refund) && refund.getFinishTime() != null
        ? refund.getFinishTime()
        : refund.getRefundTime();
  }
}
