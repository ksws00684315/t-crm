package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Read-only KOL order listing. Identity and company scope are taken from the KOL session. */
@Service
public class KolOrderService {
  private static final int MAX_PAGE = PageResult.MAX_PAGE;
  private static final int MAX_SIZE = 50;
  private static final int MAX_KEYWORD = 128;
  private static final int SUMMARY_CHUNK = 1000;
  private static final List<String> STATUSES =
      List.of("UNPAID", "PAID", "SHIPPED", "RECEIVED", "COMPLETED", "CLOSED", "UNKNOWN");

  private final OrderMapper orders;
  private final OrderItemMapper orderItems;
  private final RefundMapper refunds;
  private final CurrentKolContext current;
  private final CommissionOrderStateApi commissionStates;

  public KolOrderService(
      OrderMapper orders,
      OrderItemMapper orderItems,
      RefundMapper refunds,
      CurrentKolContext current,
      CommissionOrderStateApi commissionStates) {
    this.orders = orders;
    this.orderItems = orderItems;
    this.refunds = refunds;
    this.current = current;
    this.commissionStates = commissionStates;
  }

  public PageResult<KolOrderDTO> page(KolOrderFilterDTO filter) {
    if (invalidConditions(filter)
        || filter.page() < 1
        || filter.page() > MAX_PAGE
        || filter.size() < 1
        || filter.size() > MAX_SIZE) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolAccountDTO kol = current.kol();
    var query = baseQuery(filter, kol.id());
    List<OrderDO> pageOrders;
    long total;
    if (notBlank(filter.settlementStatus())) {
      // The order-level status is the most active own line, computed in the commission port; the
      // filter therefore resolves matching order ids first and pages them in memory. The
      // candidates reuse the exact same conditions as the plain paged query (orderNo and
      // productName included), so combined filters keep their meaning.
      List<OrderDO> candidates =
          orders.selectList(
              baseQuery(filter, kol.id()).select(OrderDO::getId).isNotNull(OrderDO::getPayTime));
      Map<String, List<Long>> byStatus = groupOrderIdsByStatus(kol.id(), candidates);
      List<Long> matched = byStatus.getOrDefault(filter.settlementStatus(), List.of());
      total = matched.size();
      pageOrders =
          matched.isEmpty()
              ? List.of()
              : orders.selectList(
                  new LambdaQueryWrapper<OrderDO>()
                      .in(
                          OrderDO::getId,
                          matched.subList(
                              Math.min((filter.page() - 1) * filter.size(), matched.size()),
                              Math.min(filter.page() * filter.size(), matched.size())))
                      .orderByDesc(OrderDO::getPayTime, OrderDO::getId));
    } else {
      var result = orders.selectPage(new Page<>(filter.page(), filter.size()), query);
      pageOrders = result.getRecords();
      total = result.getTotal();
    }
    if (pageOrders.isEmpty()) {
      return new PageResult<KolOrderDTO>(List.of(), total, filter.page(), filter.size());
    }
    List<Long> orderIds = pageOrders.stream().map(OrderDO::getId).toList();
    var lines =
        orderItems.selectList(
            new LambdaQueryWrapper<OrderItemDO>()
                .in(OrderItemDO::getOrderId, orderIds)
                .eq(OrderItemDO::getKolId, kol.id())
                .orderByAsc(OrderItemDO::getId));
    Map<Long, CommissionOrderStateApi.ItemCommissionStateDTO> statesByItem =
        commissionStates
            .listByOrderItemIds(kol.id(), lines.stream().map(OrderItemDO::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    CommissionOrderStateApi.ItemCommissionStateDTO::orderItemId, s -> s));
    return new PageResult<>(
        pageOrders.stream()
            .map(
                order -> {
                  List<OrderItemDO> ownItems =
                      lines.stream()
                          .filter(item -> order.getId().equals(item.getOrderId()))
                          .toList();
                  // Task 112: payAmount is order-level (freight included) while refundAmount sums
                  // the KOL's own lines; showing them side by side is valid only because one order
                  // belongs to one KOL (design §4.6 premise).
                  BigDecimal payAmount = order.getPayAmount();
                  BigDecimal refundAmount = sum(ownItems);
                  List<CommissionOrderStateApi.ItemCommissionStateDTO> ownStates =
                      ownItems.stream()
                          .map(item -> statesByItem.get(item.getId()))
                          .filter(Objects::nonNull)
                          .toList();
                  List<KolOrderDTO.Line> itemRows =
                      ownItems.stream()
                          .map(
                              item -> {
                                CommissionOrderStateApi.ItemCommissionStateDTO state =
                                    statesByItem.get(item.getId());
                                return new KolOrderDTO.Line(
                                    item.getItemName(),
                                    item.getSkuName(),
                                    item.getQuantity(),
                                    money(item.getPayAmount()),
                                    money(item.getRefundAmount()),
                                    settlementStatus(order, state),
                                    state == null ? null : state.rateSnapshot(),
                                    state == null ? null : state.commissionAmount());
                              })
                          .toList();
                  return new KolOrderDTO(
                      maskOrderNo(order.getWeidianOrderId()),
                      order.getPayTime(),
                      order.getOrderStatus(),
                      money(payAmount),
                      money(refundAmount),
                      order.getPayTime() == null ? null : mostActiveStatus(ownStates),
                      itemRows,
                      ownStates.stream()
                          .anyMatch(CommissionOrderStateApi.ItemCommissionStateDTO::afterSales));
                })
            .toList(),
        total,
        filter.page(),
        filter.size());
  }

  /**
   * Sales summary over the same orders the list shows for these filters (paging ignored),
   * amount-terms §5a (task 115). 订单销售额 sums the orders' pay amounts (freight and refunds included).
   * 预估退款金额 adds the full pay amount of every order with an unconcluded refund (any refund row
   * PROCESSING/UNKNOWN, the same rule the commission sync uses for after-sales) and the successful
   * refund amount of the concluded ones. 计佣金额 is their difference, so it equals the sum of the
   * per-order commission bases (§2.1). 预估总佣金 sums the rows' estimated commissions, i.e. the line
   * commission amounts the list rows show.
   */
  public KolOrderSummaryDTO summary(KolOrderFilterDTO filter) {
    if (invalidConditions(filter)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolAccountDTO kol = current.kol();
    List<OrderDO> candidates =
        orders.selectList(
            baseQuery(filter, kol.id())
                .select(OrderDO::getId, OrderDO::getPayAmount)
                .isNotNull(OrderDO::getPayTime));
    List<Long> orderIds;
    if (notBlank(filter.settlementStatus())) {
      orderIds =
          groupOrderIdsByStatus(kol.id(), candidates)
              .getOrDefault(filter.settlementStatus(), List.of());
    } else {
      orderIds = candidates.stream().map(OrderDO::getId).toList();
    }
    if (orderIds.isEmpty()) {
      return new KolOrderSummaryDTO(money(null), money(null), money(null), money(null));
    }
    Map<Long, BigDecimal> payByOrder = new HashMap<>();
    for (OrderDO order : candidates) {
      if (order.getPayAmount() != null) {
        payByOrder.merge(order.getId(), order.getPayAmount(), BigDecimal::add);
      }
    }
    Map<Long, List<RefundDO>> refundsByOrder =
        refunds
            .selectList(
                new LambdaQueryWrapper<RefundDO>()
                    .select(RefundDO::getOrderId, RefundDO::getAmount, RefundDO::getRefundStatus)
                    .in(RefundDO::getOrderId, orderIds))
            .stream()
            .collect(Collectors.groupingBy(RefundDO::getOrderId));
    BigDecimal orderSales = BigDecimal.ZERO;
    BigDecimal estimatedRefund = BigDecimal.ZERO;
    for (Long orderId : orderIds) {
      BigDecimal pay = payByOrder.getOrDefault(orderId, BigDecimal.ZERO);
      orderSales = orderSales.add(pay);
      List<RefundDO> refundRows = refundsByOrder.getOrDefault(orderId, List.of());
      boolean unconcluded =
          refundRows.stream()
              .anyMatch(
                  refund ->
                      "PROCESSING".equals(refund.getRefundStatus())
                          || "UNKNOWN".equals(refund.getRefundStatus()));
      estimatedRefund =
          estimatedRefund.add(
              unconcluded
                  ? pay
                  : refundRows.stream()
                      .filter(refund -> "SUCCESS".equals(refund.getRefundStatus()))
                      .map(
                          refund ->
                              refund.getAmount() == null ? BigDecimal.ZERO : refund.getAmount())
                      .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    BigDecimal estimatedCommission = BigDecimal.ZERO;
    for (int from = 0; from < orderIds.size(); from += SUMMARY_CHUNK) {
      List<Long> chunk = orderIds.subList(from, Math.min(from + SUMMARY_CHUNK, orderIds.size()));
      List<OrderItemDO> lines =
          orderItems.selectList(
              new LambdaQueryWrapper<OrderItemDO>()
                  .select(OrderItemDO::getId)
                  .in(OrderItemDO::getOrderId, chunk)
                  .eq(OrderItemDO::getKolId, kol.id()));
      if (lines.isEmpty()) {
        continue;
      }
      estimatedCommission =
          estimatedCommission.add(
              commissionStates
                  .listByOrderItemIds(kol.id(), lines.stream().map(OrderItemDO::getId).toList())
                  .stream()
                  .map(CommissionOrderStateApi.ItemCommissionStateDTO::commissionAmount)
                  .filter(Objects::nonNull)
                  .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
    return new KolOrderSummaryDTO(
        money(orderSales),
        money(estimatedRefund),
        money(orderSales.subtract(estimatedRefund)),
        money(estimatedCommission));
  }

  /** Conditions shared by the list and the summary; paging is validated by the list only. */
  private static boolean invalidConditions(KolOrderFilterDTO filter) {
    return (filter.payFrom() != null
            && filter.payTo() != null
            && !filter.payFrom().isBefore(filter.payTo()))
        || (filter.orderStatus() != null && !STATUSES.contains(filter.orderStatus()))
        || (filter.orderNo() != null && filter.orderNo().length() > MAX_KEYWORD)
        || (filter.productName() != null && filter.productName().length() > MAX_KEYWORD)
        || (notBlank(filter.settlementStatus())
            && !CommissionOrderStateApi.STATUS_VALUES.contains(filter.settlementStatus()));
  }

  /**
   * Line status shown to the creator. An unpaid order has no commission status yet (task 81 PR
   * review, 2026-10-30), so it shows none instead of pending verification.
   */
  private static String settlementStatus(
      OrderDO order, CommissionOrderStateApi.ItemCommissionStateDTO state) {
    if (order.getPayTime() == null) {
      return null;
    }
    return state == null ? CommissionOrderStateApi.STATUS_INVALID : state.settlementStatus();
  }

  /**
   * Order-level status: the most active own line (appendix B ladder). Also used by the admin refund
   * list's commission status (task 115).
   */
  static String mostActiveStatus(List<CommissionOrderStateApi.ItemCommissionStateDTO> states) {
    List<String> ladder =
        List.of(
            CommissionOrderStateApi.STATUS_WITHDRAWING,
            CommissionOrderStateApi.STATUS_WITHDRAWABLE,
            CommissionOrderStateApi.STATUS_FROZEN,
            CommissionOrderStateApi.STATUS_WITHDRAWN,
            CommissionOrderStateApi.STATUS_INVALID,
            CommissionOrderStateApi.STATUS_NO_COMMISSION);
    for (String status : ladder) {
      if (states.stream().anyMatch(state -> status.equals(state.settlementStatus()))) {
        return status;
      }
    }
    return CommissionOrderStateApi.STATUS_INVALID;
  }

  /**
   * Shared conditions of the plain paged query and the settlement-status candidate resolution: the
   * creator's attributed orders with the pay window, order status, order-number and product name
   * filters applied.
   */
  private LambdaQueryWrapper<OrderDO> baseQuery(KolOrderFilterDTO filter, long kolId) {
    var query =
        new LambdaQueryWrapper<OrderDO>()
            .eq(OrderDO::getKolId, kolId)
            .eq(OrderDO::getAttributionStatus, "ATTRIBUTED")
            .ge(filter.payFrom() != null, OrderDO::getPayTime, filter.payFrom())
            .lt(filter.payTo() != null, OrderDO::getPayTime, filter.payTo())
            .eq(filter.orderStatus() != null, OrderDO::getOrderStatus, filter.orderStatus())
            .orderByDesc(OrderDO::getPayTime, OrderDO::getId);
    if (notBlank(filter.orderNo())) {
      query.apply(
          "weidian_order_id ILIKE {0} ESCAPE '\\'", "%" + likeLiteral(filter.orderNo()) + "%");
    }
    if (notBlank(filter.productName())) {
      // Only the creator's own lines count; other items of the same order stay invisible.
      query.exists(
          "SELECT 1 FROM crm_order_item oi WHERE oi.order_id = crm_order.id"
              + " AND oi.kol_id = {0} AND oi.item_name ILIKE {1} ESCAPE '\\'",
          kolId, "%" + likeLiteral(filter.productName()) + "%");
    }
    return query;
  }

  private Map<String, List<Long>> groupOrderIdsByStatus(long kolId, List<OrderDO> candidates) {
    Map<Long, List<CommissionOrderStateApi.ItemCommissionStateDTO>> byOrder =
        commissionStates
            .listByKolAndOrderIds(kolId, candidates.stream().map(OrderDO::getId).toList())
            .stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    CommissionOrderStateApi.ItemCommissionStateDTO::orderId));
    Map<String, List<Long>> byStatus = new HashMap<>();
    for (OrderDO order : candidates) {
      String status = mostActiveStatus(byOrder.getOrDefault(order.getId(), List.of()));
      byStatus.computeIfAbsent(status, key -> new ArrayList<>()).add(order.getId());
    }
    return byStatus;
  }

  static String maskOrderNo(String orderNo) {
    if (orderNo == null || orderNo.isBlank()) {
      return "";
    }
    if (orderNo.length() <= 8) {
      return orderNo.substring(Math.max(0, orderNo.length() - 2));
    }
    return orderNo.substring(0, 4) + "****" + orderNo.substring(orderNo.length() - 4);
  }

  private static BigDecimal sum(List<OrderItemDO> items) {
    return items.stream()
        .map(OrderItemDO::getRefundAmount)
        .filter(value -> value != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** Escapes LIKE wildcards so the keyword matches itself, with backslash as the escape char. */
  private static String likeLiteral(String value) {
    return value.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(2)
        : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }
}
