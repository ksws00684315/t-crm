package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.api.OrderSettlementApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSettlementFlowMapper;
import com.tcrm.admin.order.mapper.OrderSettlementQueryMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order projections for the commission module (task 62); company scope is the tenant plugin's. */
@Service
public class OrderSettlementProjection implements OrderSettlementApi {
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final OrderSettlementQueryMapper settlementQueries;
  private final OrderSettlementFlowMapper settlementFlows;

  public OrderSettlementProjection(
      OrderMapper orders,
      OrderItemMapper items,
      OrderSettlementQueryMapper settlementQueries,
      OrderSettlementFlowMapper settlementFlows) {
    this.orders = orders;
    this.items = items;
    this.settlementQueries = settlementQueries;
    this.settlementFlows = settlementFlows;
  }

  @Override
  @Transactional(readOnly = true)
  public PageResult<Long> pageOrderIds(
      String orderNo, Long shopId, Collection<Long> ownKolIds, int page, int size) {
    long total =
        settlementQueries.countOrderIds(
            orderNo, shopId, ownKolIds == null || ownKolIds.isEmpty() ? null : ownKolIds);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    List<Long> ids =
        settlementQueries.pageOrderIds(
            orderNo,
            shopId,
            ownKolIds == null || ownKolIds.isEmpty() ? null : ownKolIds,
            size,
            (long) (page - 1) * size);
    return new PageResult<>(ids, total, page, size);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OrderRefDTO> listOrdersByIds(Collection<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return orders
        .selectList(
            new LambdaQueryWrapper<OrderDO>()
                .in(OrderDO::getId, orderIds)
                .orderByDesc(OrderDO::getPayTime)
                .orderByDesc(OrderDO::getId))
        .stream()
        .map(OrderSettlementProjection::toDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ItemRefDTO> listItemsByOrderIds(Collection<Long> orderIds) {
    if (orderIds == null || orderIds.isEmpty()) {
      return List.of();
    }
    return items
        .selectList(
            new LambdaQueryWrapper<OrderItemDO>()
                .in(OrderItemDO::getOrderId, orderIds)
                .orderByAsc(OrderItemDO::getOrderId)
                .orderByAsc(OrderItemDO::getId))
        .stream()
        .map(OrderSettlementProjection::toDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, BigDecimal> weidianWithdrawableByOrderIds(Collection<Long> orderIds) {
    Map<Long, BigDecimal> result = new HashMap<>();
    if (orderIds == null || orderIds.isEmpty()) {
      return result;
    }
    for (OrderDO order :
        orders.selectList(new LambdaQueryWrapper<OrderDO>().in(OrderDO::getId, orderIds))) {
      BigDecimal total =
          SettlementBasisCalculator.incomeDetailTotal(
              settlementFlows.listByRemoteOrderId(order.getShopId(), order.getWeidianOrderId()));
      if (total != null) {
        result.put(order.getId(), total);
      }
    }
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public List<KolOrderRowDTO> aggregateKolOrders(
      Collection<Long> kolIds, Instant start, Instant end) {
    if (kolIds == null || kolIds.isEmpty()) {
      return List.of();
    }
    var wrapper =
        new LambdaQueryWrapper<OrderDO>()
            .select(OrderDO::getId, OrderDO::getKolId)
            .in(OrderDO::getKolId, kolIds)
            .isNotNull(OrderDO::getPayTime)
            .ge(start != null, OrderDO::getPayTime, start)
            .lt(end != null, OrderDO::getPayTime, end);
    List<OrderDO> paidOrders = orders.selectList(wrapper);
    if (paidOrders.isEmpty()) {
      return List.of();
    }
    Set<Long> orderIds = new HashSet<>();
    for (OrderDO order : paidOrders) {
      orderIds.add(order.getId());
    }
    // Quantity counts the KOL's own lines only: an order may mix lines of several KOLs.
    record Line(Long kolId, int quantity) {}
    var lines =
        items
            .selectList(
                new LambdaQueryWrapper<OrderItemDO>()
                    .select(
                        OrderItemDO::getOrderId, OrderItemDO::getKolId, OrderItemDO::getQuantity)
                    .in(OrderItemDO::getOrderId, orderIds)
                    .in(OrderItemDO::getKolId, kolIds))
            .stream()
            .map(row -> new Line(row.getKolId(), row.getQuantity() == null ? 0 : row.getQuantity()))
            .toList();
    var byOrderLine = new java.util.HashMap<Long, List<Long>>();
    var linesById = new java.util.HashMap<Long, List<Line>>();
    // Order-level attribution: crm_order.kol_id is the order's single KOL (conflict orders have
    // null and are excluded by the wrapper); map every paid order to that KOL.
    for (OrderDO order : paidOrders) {
      byOrderLine.computeIfAbsent(order.getKolId(), k -> new ArrayList<>()).add(order.getId());
    }
    for (Line line : lines) {
      linesById.computeIfAbsent(line.kolId(), k -> new ArrayList<>()).add(line);
    }
    List<KolOrderRowDTO> rows = new ArrayList<>();
    for (var entry : byOrderLine.entrySet()) {
      int quantity =
          linesById.getOrDefault(entry.getKey(), List.of()).stream().mapToInt(Line::quantity).sum();
      rows.add(new KolOrderRowDTO(entry.getKey(), entry.getValue().size(), quantity));
    }
    return rows;
  }

  private static OrderRefDTO toDto(OrderDO row) {
    return new OrderRefDTO(
        row.getId(),
        row.getWeidianOrderId(),
        row.getShopId(),
        row.getPayTime(),
        row.getOrderStatus(),
        row.getPayAmount());
  }

  private static ItemRefDTO toDto(OrderItemDO row) {
    return new ItemRefDTO(
        row.getId(),
        row.getOrderId(),
        row.getItemName(),
        row.getSkuName(),
        row.getQuantity() == null ? 0 : row.getQuantity(),
        row.getPayAmount(),
        row.getRefundAmount(),
        row.getKolId(),
        row.getProductId());
  }
}
