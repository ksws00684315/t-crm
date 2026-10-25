package com.tcrm.admin.order.api;

import java.util.Collection;
import java.util.List;

/**
 * Order item display info for other modules (task 63: withdrawal allocations snapshot what the
 * finance pages show — order no, item name, SKU and product image).
 */
public interface OrderItemInfoApi {
  /**
   * The existing order items among {@code ids}; unknown IDs are skipped. An empty collection
   * returns an empty list without querying.
   */
  List<OrderItemInfoDTO> listByIds(Collection<Long> ids);

  /**
   * @param orderNo Weidian order number of the item's order
   * @param weidianItemId exclusive (copied) Weidian item id; null when the line has none
   * @param skuId Weidian SKU id; null when the line has none
   */
  record OrderItemInfoDTO(
      Long id,
      Long orderId,
      String orderNo,
      String weidianItemId,
      String skuId,
      String itemName,
      String skuName,
      Long productId,
      Long kolId) {}
}
