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
        || (filter.orderNo() != null && filter.orderNo().length() > 128)
        || (filter.productName() != null && filter.productName().length() > 128)
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
    String orderNo = trimToNull(filter.orderNo());
    if (orderNo != null) {
      int mask = orderNo.indexOf('*');
      if (mask < 0) {
        query.like(OrderDO::getWeidianOrderId, orderNo);
      } else {
        String prefix = orderNo.substring(0, mask);
        String suffix = orderNo.substring(orderNo.lastIndexOf('*') + 1);
        query.likeRight(!prefix.isEmpty(), OrderDO::getWeidianOrderId, prefix);
        query.likeLeft(!suffix.isEmpty(), OrderDO::getWeidianOrderId, suffix);
      }
    }
    String productName = trimToNull(filter.productName());
    if (productName != null) {
      query.exists(
          "SELECT 1 FROM crm_order_item oi"
              + " WHERE oi.order_id = crm_order.id AND oi.kol_id = {0}"
              + " AND oi.is_deleted = FALSE"
              + " AND POSITION({1} IN LOWER(oi.item_name)) > 0",
          kol.id(), productName.toLowerCase(java.util.Locale.ROOT));
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
                      null,
                      moneyText(payAmount),
                      moneyText(refundAmount),
                      ownItems.stream()
                          .map(
                              item ->
                                  new KolOrderDTO.Line(
                                      item.getItemName(),
                                      item.getSkuName(),
                                      item.getQuantity(),
                                      moneyText(item.getPayAmount()),
                                      moneyText(item.getRefundAmount()),
                                      null,
                                      null,
                                      null))
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

  private static String moneyText(BigDecimal value) {
    return (value == null
            ? BigDecimal.ZERO.setScale(2)
            : value.setScale(2, java.math.RoundingMode.HALF_UP))
        .toPlainString();
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
