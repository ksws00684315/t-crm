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

  /**
   * Returns the product-level commission rates among {@code productIds} (task 62, design §8.3
   * [D7]); products without a rate are skipped. An empty collection returns an empty list without
   * querying.
   *
   * @param productIds {@code crm_product.id} values
   */
  List<ProductRateDTO> listRates(Collection<Long> productIds);

  /**
   * Identity, display fields and main image of an original product.
   *
   * @param id {@code crm_product.id}
   * @param code original product code
   * @param name product name
   * @param imageUrl main image URL; null when the product has none
   * @param status 1 on sale, 0 off sale
   */
  record ProductRefDTO(Long id, String code, String name, String imageUrl, Integer status) {}
}
