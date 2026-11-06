package com.tcrm.admin.order.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * Order item display info for other modules (task 63: withdrawal allocations snapshot what the
 * finance pages show — order no, item name, SKU and product image). Task 115 adds order-level
 * amounts for the withdrawal detail's 订单金额 column.
 */
public interface OrderItemInfoApi {
  /**
   * The existing order items among {@code ids}; unknown IDs are skipped. An empty collection
   * returns an empty list without querying.
   */
  List<OrderItemInfoDTO> listByIds(Collection<Long> ids);

  /**
   * Order-level amounts of the existing orders among {@code orderIds}; unknown IDs are skipped. An
   * empty collection returns an empty list without querying. {@code itemAmount} is 商品总价 (the sum of
   * historical unit prices times quantities, null when any line misses its price or quantity) and
   * {@code freight} the order freight; 订单金额 = itemAmount + freight (amount terms §2).
   */
  List<OrderAmountDTO> listOrderAmounts(Collection<Long> orderIds);

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

  /** Order-level amounts of one order (amount terms §2, task 115). */
  record OrderAmountDTO(long orderId, BigDecimal itemAmount, BigDecimal freight) {}
}
