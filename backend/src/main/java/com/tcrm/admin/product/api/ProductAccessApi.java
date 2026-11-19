package com.tcrm.admin.product.api;

import java.util.Collection;
import java.util.List;

/** Read-only original-product lookups for other modules (task 40: order images, match evidence). */
public interface ProductAccessApi {
  /**
   * Returns the product in the caller's current tenant scope, or null when the ID is unknown.
   * Deleted products are returned too, because historical orders still reference them.
   *
   * @param productId {@code crm_product.id}
   */
  ProductRefDTO getProduct(Long productId);

  /**
   * Returns the existing products among {@code ids}, in no particular order; unknown IDs are
   * skipped. An empty collection returns an empty list without querying.
   *
   * @param productIds {@code crm_product.id} values
   */
  List<ProductRefDTO> listProducts(Collection<Long> productIds);

  /** Current original products and effective creator links in the ranking's visible scope. */
  List<RankingInventory> rankingInventory(RankingInventoryScope scope);

  record RankingInventoryScope(
      long tenantId,
      List<Long> shopIds,
      Long ownerId,
      List<Long> creatorIds,
      List<Long> inviterIds,
      String keyword,
      Collection<Long> rankedProductIds) {}

  /** A null product ID denotes the total; per-product rows carry the two current counts. */
  record RankingInventory(
      Long productId, long totalSpuCount, long onSaleShopCount, long linkedCreatorCount) {}

  /**
   * Identity, display fields and main image of an original product.
   *
   * @param id {@code crm_product.id}
   * @param code original product code
   * @param name product name
   * @param imageUrl main image URL; null when the product has none
   * @param status 1 on sale, 0 off sale
   * @param sourceItemId original Weidian item ID from the source link
   */
  record ProductRefDTO(
      Long id,
      String code,
      String name,
      String imageUrl,
      Integer status,
      String category,
      String sourceItemId) {
    public ProductRefDTO(
        Long id, String code, String name, String imageUrl, Integer status, String category) {
      this(id, code, name, imageUrl, status, category, null);
    }

    public ProductRefDTO(Long id, String code, String name, String imageUrl, Integer status) {
      this(id, code, name, imageUrl, status, null);
    }
  }
}
