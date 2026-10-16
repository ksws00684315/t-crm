package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queries and human decisions, with tenant SQL filtering and payment-time OWN scope. */
@Service
public class OrderService {
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final RefundMapper refunds;
  private final KolApi kols;
  private final OrderAttribution attribution;
  private final CurrentUserContext current;

  public OrderService(
      OrderMapper orders,
      OrderItemMapper items,
      RefundMapper refunds,
      KolApi kols,
      OrderAttribution attribution,
      CurrentUserContext current) {
    this.orders = orders;
    this.items = items;
    this.refunds = refunds;
    this.kols = kols;
    this.attribution = attribution;
    this.current = current;
  }

  public PageResult<OrderDTO> page(OrderFilterDTO filter, boolean unmatched) {
    var user = current.user();
    var query =
        new LambdaQueryWrapper<OrderDO>()
            .eq(filter.shopId() != null, OrderDO::getShopId, filter.shopId())
            .ge(filter.payFrom() != null, OrderDO::getPayTime, filter.payFrom())
            .lt(filter.payTo() != null, OrderDO::getPayTime, filter.payTo())
            .eq(filter.status() != null, OrderDO::getOrderStatus, filter.status())
            .eq(filter.attribution() != null, OrderDO::getAttributionStatus, filter.attribution())
            .eq(user.ownScope(), OrderDO::getBdUserId, user.id());
    if (unmatched) {
      query.in(OrderDO::getAttributionStatus, List.of("CONFLICT", "UNMATCHED"));
    }
    if (filter.kolCode() != null && !filter.kolCode().isBlank()) {
      var kol = kols.getByCode(filter.kolCode());
      if (kol == null) {
        return new PageResult<>(List.of(), 0, filter.page(), filter.size());
      }
      query.eq(OrderDO::getKolId, kol.id());
    }
    query.orderByDesc(OrderDO::getPayTime, OrderDO::getId);
    var result = orders.selectPage(new Page<>(filter.page(), filter.size()), query);
    if (result.getRecords().isEmpty()) {
      return new PageResult<>(List.of(), result.getTotal(), filter.page(), filter.size());
    }
    var ids = result.getRecords().stream().map(OrderDO::getId).toList();
    Map<Long, List<OrderItemDO>> lines =
        items
            .selectList(
                new LambdaQueryWrapper<OrderItemDO>()
                    .in(OrderItemDO::getOrderId, ids)
                    .orderByAsc(OrderItemDO::getId))
            .stream()
            .collect(Collectors.groupingBy(OrderItemDO::getOrderId));
    Map<Long, BigDecimal> totals =
        refunds
            .selectList(
                new LambdaQueryWrapper<RefundDO>()
                    .in(RefundDO::getOrderId, ids)
                    .eq(RefundDO::getRawStatus, "10"))
            .stream()
            .collect(Collectors.toMap(RefundDO::getOrderId, RefundDO::getAmount, BigDecimal::add));
    return new PageResult<>(
        result.getRecords().stream()
            .map(
                o ->
                    new OrderDTO(
                        o.getId(),
                        o.getShopId(),
                        o.getWeidianOrderId(),
                        o.getKolId(),
                        o.getBdUserId(),
                        o.getOrderStatus(),
                        o.getAttributionStatus(),
                        o.getOrderTime(),
                        o.getPayTime(),
                        o.getTotalAmount(),
                        o.getPayAmount(),
                        totals.getOrDefault(o.getId(), BigDecimal.ZERO.setScale(2)),
                        lines.getOrDefault(o.getId(), List.of()).stream()
                            .map(OrderService::line)
                            .toList()))
            .toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  @Transactional(rollbackFor = Exception.class)
  public void assign(Long id, Long kolId, boolean nonKol, String reason) {
    if (reason == null
        || reason.isBlank()
        || reason.length() > 255
        || (nonKol == (kolId != null))) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    var order = locked(id);
    if (order.getPayTime() == null) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    if (kolId != null && kols.listByIds(List.of(kolId)).isEmpty()) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var lines = lines(id);
    attribution.manual(order, lines, kolId, reason);
    persist(order, lines);
  }

  @Transactional(rollbackFor = Exception.class)
  public void reattribute(Long id) {
    var order = locked(id);
    var lines = lines(id);
    attribution.apply(order, lines, true);
    persist(order, lines);
  }

  private OrderDO locked(Long id) {
    var row =
        orders.selectOne(
            new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getId, id).last("FOR UPDATE"));
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    var user = current.user();
    if (user.ownScope() && !Objects.equals(row.getBdUserId(), user.id())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    return row;
  }

  private List<OrderItemDO> lines(Long id) {
    return items.selectList(new LambdaQueryWrapper<OrderItemDO>().eq(OrderItemDO::getOrderId, id));
  }

  private void persist(OrderDO order, List<OrderItemDO> lines) {
    orders.updateById(order);
    lines.forEach(items::updateById);
  }

  private static OrderItemDTO line(OrderItemDO i) {
    return new OrderItemDTO(
        i.getId(),
        i.getSubOrderId(),
        i.getWeidianItemId(),
        i.getSkuId(),
        i.getItemName(),
        i.getSkuName(),
        i.getQuantity(),
        i.getPayAmount(),
        i.getRefundAmount(),
        i.getKolId(),
        i.getProductId(),
        i.getKolItemId(),
        i.getAttributionType(),
        i.getManualReason());
  }
}
