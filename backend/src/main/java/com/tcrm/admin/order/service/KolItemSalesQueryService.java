package com.tcrm.admin.order.service;

import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.model.KolItemSalesRow;
import com.tcrm.admin.product.api.KolItemSalesQuery;
import com.tcrm.admin.product.api.KolItemSalesTotal;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** Order-module implementation of creator-item sales totals (task 39). */
@Service
public class KolItemSalesQueryService implements KolItemSalesQuery {
  private final OrderItemMapper items;

  public KolItemSalesQueryService(OrderItemMapper items) {
    this.items = items;
  }

  @Override
  public List<KolItemSalesTotal> sumByKolItemIds(Collection<Long> kolItemIds) {
    if (kolItemIds == null || kolItemIds.isEmpty()) {
      return List.of();
    }
    return items.sumByKolItemIds(kolItemIds).stream()
        .map(KolItemSalesQueryService::toTotal)
        .toList();
  }

  private static KolItemSalesTotal toTotal(KolItemSalesRow row) {
    return new KolItemSalesTotal(
        row.getKolItemId(),
        row.getSalesQuantity() == null ? 0L : row.getSalesQuantity(),
        row.getPayAmount(),
        row.getRefundAmount());
  }
}
