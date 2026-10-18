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
   * Copies one item into the target shop at the original price. Returns the new Weidian item id and
   * an optional detail-save warning. Throws when the source item lacks name/price (must not call
   * {@code vdian.item.add}) or when {@code addItem} itself fails.
   */
  CopyResult copy(long sourceShopId, String sourceItemId, long targetShopId);

  /**
   * Returns item summary when the shop owns {@code weidianItemId}. Empty only when Weidian
   * explicitly reports the item does not exist / does not belong to the shop. Other Weidian errors
   * throw {@link com.tcrm.admin.shared.BusinessException}.
   */
  Optional<ProbedItem> probe(long shopId, String weidianItemId);

  /** True when the shop has a live {@code AUTHORIZED} Weidian authorization row. */
  boolean isShopAuthorized(long shopId);

  record ProbedItem(String itemId, String name, BigDecimal price) {}

  /**
   * @param newItemId digit-string id returned by {@code vdian.item.add}
   * @param detailWarning null on full success; Chinese warning when detail save failed after add
   */
  record CopyResult(String newItemId, String detailWarning) {}
}
