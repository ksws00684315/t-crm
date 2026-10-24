package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.api.OrderItemInfoApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order item display info for other modules (task 63). Company isolation is the tenant plugin's
 * job; the caller runs inside the company scope of the business record it is writing.
 */
@Service
public class OrderItemInfoService implements OrderItemInfoApi {
  private final OrderItemMapper items;
  private final OrderMapper orders;

  public OrderItemInfoService(OrderItemMapper items, OrderMapper orders) {
    this.items = items;
    this.orders = orders;
  }

  @Override
  @Transactional(readOnly = true)
  public List<OrderItemInfoDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<OrderItemDO> rows =
        items.selectList(new LambdaQueryWrapper<OrderItemDO>().in(OrderItemDO::getId, ids));
    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> orderIds = rows.stream().map(OrderItemDO::getOrderId).distinct().toList();
    Map<Long, String> orderNoById = new HashMap<>();
    for (OrderDO order : orders.selectBatchIds(orderIds)) {
      orderNoById.put(order.getId(), order.getWeidianOrderId());
    }
    return rows.stream()
        .map(
            row ->
                new OrderItemInfoDTO(
                    row.getId(),
                    row.getOrderId(),
                    orderNoById.get(row.getOrderId()),
                    row.getWeidianItemId(),
                    row.getSkuId(),
                    row.getItemName(),
                    row.getSkuName(),
                    row.getProductId(),
                    row.getKolId()))
        .toList();
  }
}
