package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.List;
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

  public KolOrderService(
      OrderMapper orders, OrderItemMapper orderItems, CurrentKolContext current) {
    this.orders = orders;
    this.orderItems = orderItems;
    this.current = current;
  }

  public PageResult<KolOrderDTO> page(KolOrderFilterDTO filter) {
    if ((filter.payFrom() != null
            && filter.payTo() != null
            && !filter.payFrom().isBefore(filter.payTo()))
        || (filter.orderStatus() != null && !STATUSES.contains(filter.orderStatus()))
        || (filter.orderNo() != null && filter.orderNo().length() > MAX_KEYWORD)
        || (filter.productName() != null && filter.productName().length() > MAX_KEYWORD)
        || filter.page() < 1
        || filter.page() > MAX_PAGE
        || filter.size() < 1
        || filter.size() > MAX_SIZE) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolAccountDTO kol = current.kol();
    var query =
        new LambdaQueryWrapper<OrderDO>()
            .eq(OrderDO::getKolId, kol.id())
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
          kol.id(), "%" + likeLiteral(filter.productName()) + "%");
    }
    var result = orders.selectPage(new Page<>(filter.page(), filter.size()), query);
    if (result.getRecords().isEmpty()) {
      return new PageResult<KolOrderDTO>(
          List.of(), result.getTotal(), filter.page(), filter.size());
    }
    List<Long> orderIds = result.getRecords().stream().map(OrderDO::getId).toList();
    var lines =
        orderItems.selectList(
            new LambdaQueryWrapper<OrderItemDO>()
                .in(OrderItemDO::getOrderId, orderIds)
                .eq(OrderItemDO::getKolId, kol.id())
                .orderByAsc(OrderItemDO::getId));
    return new PageResult<>(
        result.getRecords().stream()
            .map(
                order -> {
                  List<OrderItemDO> ownItems =
                      lines.stream()
                          .filter(item -> order.getId().equals(item.getOrderId()))
                          .toList();
                  BigDecimal payAmount = sum(ownItems, true);
                  BigDecimal refundAmount = sum(ownItems, false);
                  return new KolOrderDTO(
                      maskOrderNo(order.getWeidianOrderId()),
                      order.getPayTime(),
                      order.getOrderStatus(),
                      money(payAmount),
                      money(refundAmount),
                      ownItems.stream()
                          .map(
                              item ->
                                  new KolOrderDTO.Line(
                                      item.getItemName(),
                                      item.getSkuName(),
                                      item.getQuantity(),
                                      money(item.getPayAmount()),
                                      money(item.getRefundAmount())))
                          .toList());
                })
            .toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
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
