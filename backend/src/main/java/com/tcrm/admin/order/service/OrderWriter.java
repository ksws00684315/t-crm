package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianBusinessApi.RefundDetailResponse;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only; all external calls finish before entering this service. */
@Service
public class OrderWriter {
  private final OrderMapper orders;
  private final OrderItemMapper items;
  private final RefundMapper refunds;
  private final OrderAttribution attribution;

  public OrderWriter(
      OrderMapper orders,
      OrderItemMapper items,
      RefundMapper refunds,
      OrderAttribution attribution) {
    this.orders = orders;
    this.items = items;
    this.refunds = refunds;
    this.attribution = attribution;
  }

  public OrderDO find(long shopId, String remoteId) {
    return orders.selectOne(
        new LambdaQueryWrapper<OrderDO>()
            .eq(OrderDO::getShopId, shopId)
            .eq(OrderDO::getWeidianOrderId, remoteId));
  }

  public RefundDO findRefund(String weidianRefundNo) {
    return refunds.selectOne(
        new LambdaQueryWrapper<RefundDO>().eq(RefundDO::getWeidianRefundNo, weidianRefundNo));
  }

  @Transactional(rollbackFor = Exception.class)
  public void save(OrderPayloadDTO payload) {
    var next = payload.order();
    var existing =
        orders.selectOne(
            new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getShopId, next.getShopId())
                .eq(OrderDO::getWeidianOrderId, next.getWeidianOrderId())
                .last("FOR UPDATE"));
    if (existing == null) {
      orders.insert(next);
    } else {
      next.setId(existing.getId());
      next.setTenantId(existing.getTenantId());
      next.setSettledTime(existing.getSettledTime());
      orders.updateById(next);
    }
    var previous = listItems(next.getId());
    for (var line : payload.items()) {
      line.setOrderId(next.getId());
      var old =
          previous.stream().filter(i -> i.getSubOrderId().equals(line.getSubOrderId())).findFirst();
      if (old.isPresent()) {
        var row = old.get();
        row.setWeidianItemId(line.getWeidianItemId());
        row.setSkuId(line.getSkuId());
        row.setItemName(line.getItemName());
        row.setSkuName(line.getSkuName());
        row.setQuantity(line.getQuantity());
        row.setPayAmount(line.getPayAmount());
        items.updateById(row);
      } else {
        items.insert(line);
      }
    }
    var all = listItems(next.getId());
    attribution.apply(next, all, false);
    orders.updateById(next);
    all.forEach(items::updateById);
  }

  public List<OrderItemDO> listItems(Long orderId) {
    return items.selectList(
        new LambdaQueryWrapper<OrderItemDO>()
            .eq(OrderItemDO::getOrderId, orderId)
            .orderByAsc(OrderItemDO::getId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveRefund(long shopId, RefundDetailResponse detail) {
    var order =
        orders.selectOne(
            new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getShopId, shopId)
                .eq(OrderDO::getWeidianOrderId, detail.orderId())
                .last("FOR UPDATE"));
    if (order == null) {
      throw new IllegalStateException("Refund order is missing");
    }
    var row =
        refunds.selectOne(
            new LambdaQueryWrapper<RefundDO>().eq(RefundDO::getWeidianRefundNo, detail.refundNo()));
    boolean create = row == null;
    if (create) {
      row = new RefundDO();
    }
    if (!create && !Objects.equals(row.getOrderId(), order.getId())) {
      throw new IllegalStateException("Refund identity conflict");
    }
    row.setOrderId(order.getId());
    row.setWeidianRefundNo(OrderNormalizer.required(detail.refundNo(), 32));
    row.setAmount(OrderNormalizer.money(detail.refundFeeFen().movePointLeft(2)));
    row.setRefundTime(detail.createdAt());
    row.setFinishTime(detail.finishedAt());
    var basic = detail.raw() == null ? null : detail.raw().get("refundBasicInfo");
    row.setRawStatus(OrderNormalizer.required(OrderNormalizer.text(basic, "status"), 32));
    var all = listItems(order.getId());
    Long itemId = null;
    var remoteItems = basic == null ? null : basic.get("itemList");
    if (remoteItems != null && remoteItems.isArray() && remoteItems.size() == 1) {
      var remote = remoteItems.get(0);
      String sub = OrderNormalizer.text(remote, "orderDescId");
      var matches = all.stream().filter(i -> i.getSubOrderId().equals(sub)).toList();
      if (matches.isEmpty()) {
        String remoteItem =
            OrderNormalizer.first(
                OrderNormalizer.text(remote, "curShopItemId"),
                OrderNormalizer.text(remote, "itemId"));
        String remoteSku =
            OrderNormalizer.first(
                OrderNormalizer.text(remote, "curShopSkuId"),
                OrderNormalizer.text(remote, "skuId"));
        matches =
            all.stream()
                .filter(
                    i ->
                        Objects.equals(i.getWeidianItemId(), remoteItem)
                            && (remoteSku == null || Objects.equals(i.getSkuId(), remoteSku)))
                .toList();
      }
      if (matches.size() == 1) {
        itemId = matches.getFirst().getId();
      }
    }
    row.setOrderItemId(itemId);
    if (create) {
      refunds.insert(row);
    } else {
      refunds.updateById(row);
    }
    refreshCompletedRefundTotals(order, all);
  }

  /**
   * Inserts one completed refund from Excel import ({@code raw_status = 10}). Resolves {@code
   * order_item_id} when {@code subOrderId} uniquely matches a line; otherwise leaves it null.
   *
   * @return {@code false} when the order is missing in this shop
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean saveImportedRefund(
      long shopId, String weidianOrderId, String subOrderId, RefundDO row) {
    var locked =
        orders.selectOne(
            new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getShopId, shopId)
                .eq(OrderDO::getWeidianOrderId, weidianOrderId)
                .last("FOR UPDATE"));
    if (locked == null) {
      return false;
    }
    var all = listItems(locked.getId());
    Long itemId = null;
    if (subOrderId != null && !subOrderId.isBlank()) {
      var matches = all.stream().filter(i -> i.getSubOrderId().equals(subOrderId)).toList();
      if (matches.size() == 1) {
        itemId = matches.getFirst().getId();
      }
    }
    row.setOrderId(locked.getId());
    row.setOrderItemId(itemId);
    row.setRawStatus("10");
    refunds.insert(row);
    refreshCompletedRefundTotals(locked, listItems(locked.getId()));
    return true;
  }

  private void refreshCompletedRefundTotals(OrderDO order, List<OrderItemDO> all) {
    var completed =
        refunds.selectList(
            new LambdaQueryWrapper<RefundDO>()
                .eq(RefundDO::getOrderId, order.getId())
                .eq(RefundDO::getRawStatus, "10"));
    for (var item : all) {
      item.setRefundAmount(
          completed.stream()
              .filter(f -> Objects.equals(f.getOrderItemId(), item.getId()))
              .map(RefundDO::getAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add));
      items.updateById(item);
    }
    order.setLastSyncTime(Instant.now());
    orders.updateById(order);
  }
}
