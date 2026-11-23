package com.tcrm.admin.order.service;

import com.tcrm.admin.order.api.OrderChainCommissionApi;
import com.tcrm.admin.order.api.OrderChainCommissionApi.OrderCommissionChainDTO;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Derived payment accrual and refund events for the chain. These do not post wallet entries. */
record OrderChainAccrual(String status, BigDecimal amount, List<OrderChainDTO.FlowDTO> flows) {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
  private static final Comparator<Instant> EVENT_TIME =
      Comparator.nullsLast(Comparator.naturalOrder());

  static OrderChainAccrual of(
      OrderDO order,
      List<OrderItemDO> items,
      List<RefundDO> refunds,
      OrderCommissionChainDTO commission,
      String shopName) {
    if (order.getPayTime() == null) {
      if (!"UNPAID".equals(order.getOrderStatus())
          || "part_pay".equals(order.getRawStatus())
          || order.getTotalAmount() == null) {
        return new OrderChainAccrual(null, null, List.of());
      }
      return new OrderChainAccrual(
          null,
          null,
          List.of(
              shopFlow(
                  "SP" + order.getWeidianOrderId(),
                  null,
                  shopName,
                  OrderChainCommissionApi.DIRECTION_INCREASE,
                  order.getTotalAmount(),
                  order.getOrderTime(),
                  "PRE_INCOME")));
    }
    if (items.isEmpty()) {
      return new OrderChainAccrual(null, null, List.of());
    }

    var effectiveRefunds =
        refunds.stream()
            .filter(r -> "SUCCESS".equals(r.getRefundStatus()))
            .filter(r -> r.getId() != null)
            .sorted(
                Comparator.comparing(OrderChainAccrual::eventTime, EVENT_TIME)
                    .thenComparing(RefundDO::getId))
            .toList();
    boolean hasPending = refunds.stream().anyMatch(OrderChainAccrual::pending);

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
    var flows = new ArrayList<OrderChainDTO.FlowDTO>();
    if (order.getPayAmount() != null) {
      flows.add(
          shopFlow(
              "SR" + order.getWeidianOrderId(),
              null,
              shopName,
              OrderChainCommissionApi.DIRECTION_INCREASE,
              order.getPayAmount(),
              order.getPayTime(),
              "PENDING_SETTLEMENT"));
    }
    if (gross != null) {
      flows.add(
          new OrderChainDTO.FlowDTO(
              "CA" + order.getWeidianOrderId(),
              null,
              shopName,
              "COMMISSION_ACCRUAL",
              OrderChainCommissionApi.DIRECTION_INCREASE,
              gross,
              order.getPayTime(),
              null,
              null,
              "PENDING_SETTLEMENT",
              "FROZEN",
              null,
              null));
    }

    // The application records an expected refund without moving cash or changing accrual.
    refunds.stream()
        .filter(refund -> refund.getId() != null && refund.getRefundTime() != null)
        .filter(refund -> refund.getAmount() != null && refund.getAmount().signum() > 0)
        .sorted(Comparator.comparing(RefundDO::getId))
        .map(
            refund ->
                new OrderChainDTO.FlowDTO(
                    "SRA" + refund.getId(),
                    refund.getOrderItemId(),
                    shopName,
                    "REFUND_REQUEST",
                    null,
                    refund.getAmount(),
                    refund.getRefundTime(),
                    null,
                    null,
                    pending(refund) ? "REFUNDING" : "REFUND_REQUESTED",
                    null,
                    null,
                    null))
        .distinct()
        .forEach(flows::add);
    BigDecimal remaining = gross;
    var seen = new HashSet<Long>();
    for (RefundDO refund : effectiveRefunds) {
      if (!seen.add(refund.getId())) {
        continue;
      }
      flows.add(
          shopFlow(
              "SRF" + refund.getId(),
              refund.getOrderItemId(),
              shopName,
              OrderChainCommissionApi.DIRECTION_DECREASE,
              refund.getAmount(),
              eventTime(refund),
              "DEDUCTION"));
      BigDecimal expense = null;
      if (remaining != null && basis != null && applyRefund(basis, refund)) {
        BigDecimal next = amount(basis, rates);
        expense = remaining.subtract(next);
        remaining = next;
      } else {
        remaining = null;
      }
      flows.add(
          new OrderChainDTO.FlowDTO(
              "CR" + refund.getId(),
              refund.getOrderItemId(),
              shopName,
              "COMMISSION_REFUND",
              OrderChainCommissionApi.DIRECTION_DECREASE,
              expense,
              eventTime(refund),
              null,
              null,
              "DEDUCTION",
              "INVALID",
              null,
              null));
    }
    flows.sort(Comparator.comparing(OrderChainDTO.FlowDTO::businessAt, EVENT_TIME));
    if (hasPending || commission.afterSales() || !effectiveRefunds.isEmpty()) {
      return new OrderChainAccrual(
          hasPending || commission.afterSales() ? "REFUNDING" : "REFUNDED",
          remaining,
          List.copyOf(flows));
    }
    if (gross == null) {
      return new OrderChainAccrual("UNVERIFIED", null, List.copyOf(flows));
    }
    BigDecimal released = releasedCommission(commission);
    boolean frozen = released.compareTo(gross) < 0 || released.signum() == 0;
    return new OrderChainAccrual(
        frozen ? "FROZEN" : "POSTED", frozen ? ZERO : released, List.copyOf(flows));
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
                OrderChainCommissionApi.DIRECTION_DECREASE.equals(flow.direction())
                    ? flow.amountCny().negate()
                    : flow.amountCny())
        .reduce(ZERO, BigDecimal::add)
        .max(ZERO);
  }

  private static OrderChainDTO.FlowDTO shopFlow(
      String reference,
      Long itemId,
      String shopName,
      String direction,
      BigDecimal value,
      Instant time,
      String status) {
    return new OrderChainDTO.FlowDTO(
        reference,
        itemId,
        shopName,
        OrderChainCommissionApi.FLOW_SHOP_RECEIVABLE,
        direction,
        value,
        time,
        null,
        null,
        status,
        null,
        null,
        null);
  }

  private static boolean applyRefund(Map<Long, BigDecimal> basis, RefundDO refund) {
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
    return false;
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
    return OrderCommissionSync.isUnconcludedRefund(refund);
  }

  private static Instant eventTime(RefundDO refund) {
    return refund.getFinishTime();
  }
}
