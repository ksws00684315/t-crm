package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
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
  private static final List<String> STATUSES =
      List.of("UNPAID", "PAID", "SHIPPED", "RECEIVED", "COMPLETED", "CLOSED", "UNKNOWN");

  private final OrderMapper orders;
  private final OrderItemMapper orderItems;
  private final CurrentKolContext current;
  private final CommissionOrderStateApi commissionStates;

  public KolOrderService(
      OrderMapper orders,
      OrderItemMapper orderItems,
      CurrentKolContext current,
      CommissionOrderStateApi commissionStates) {
    this.orders = orders;
    this.orderItems = orderItems;
    this.current = current;
    this.commissionStates = commissionStates;
  }

  public PageResult<KolOrderDTO> page(KolOrderFilterDTO filter) {
    if ((filter.payFrom() != null
            && filter.payTo() != null
            && !filter.payFrom().isBefore(filter.payTo()))
        || (filter.orderStatus() != null && !STATUSES.contains(filter.orderStatus()))
        || (filter.orderNo() != null && filter.orderNo().length() > MAX_KEYWORD)
        || (filter.productName() != null && filter.productName().length() > MAX_KEYWORD)
        || (notBlank(filter.settlementStatus())
            && !CommissionOrderStateApi.STATUS_VALUES.contains(filter.settlementStatus()))
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
                  BigDecimal payAmount = sum(ownItems, true);
                  BigDecimal refundAmount = sum(ownItems, false);
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
                      itemRows);
                })
            .toList(),
        total,
        filter.page(),
        filter.size());
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

  /** Order-level status: the most active own line (appendix B ladder). */
  private static String mostActiveStatus(
      List<CommissionOrderStateApi.ItemCommissionStateDTO> states) {
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

  private static BigDecimal sum(List<OrderItemDO> items, boolean pay) {
    return items.stream()
        .map(item -> pay ? item.getPayAmount() : item.getRefundAmount())
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
