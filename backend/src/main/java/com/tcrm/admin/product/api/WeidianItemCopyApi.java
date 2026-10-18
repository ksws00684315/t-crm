package com.tcrm.admin.product.api;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Weidian item copy and probe for the external product-link flow (task 17b). Implemented in the
 * {@code integration} module so {@code product} does not depend on {@code integration.api} (avoids
 * the product ↔ integration cycle via {@link ShopAccessApi}).
 */
public interface WeidianItemCopyApi {
  /**
   * Copies one item into the target shop at the original price. Returns the new Weidian item id
   * (digit string).
   */
  String copy(long sourceShopId, String sourceItemId, long targetShopId);

  /**
   * Returns item summary when the shop owns {@code weidianItemId}; empty when the shop does not or
   * the call fails.
   */
  Optional<ProbedItem> probe(long shopId, String weidianItemId);

  record ProbedItem(String itemId, String name, BigDecimal price) {}
}
