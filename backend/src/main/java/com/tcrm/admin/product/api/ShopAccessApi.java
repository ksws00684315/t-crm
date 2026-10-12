package com.tcrm.admin.product.api;

import java.util.List;

/** Shop lookup exposed to modules that need to bind an external integration to a CRM shop. */
public interface ShopAccessApi {
  /**
   * Returns a shop in the caller's current tenant scope, or {@code null} when it is not visible.
   */
  ShopAccessDTO findShop(Long shopId);

  /** Lists non-deleted shops visible to the caller's current tenant scope. */
  List<ShopAccessDTO> listShops();
}
