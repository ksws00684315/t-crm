package com.tcrm.admin.kol.api;

import java.util.Collection;
import java.util.Map;

/**
 * Narrow shop lookup used by copy-token creation (task 28 §3.1). Implemented in the product module
 * so {@code kol} never depends on {@code product.api} (ArchitectureTest forbids that cycle).
 * Company isolation is applied by the tenant plugin from the login context.
 */
public interface CopyTargetShopApi {
  /**
   * Ensures the shop exists in the caller's company, is not deleted, and is enabled.
   *
   * @throws com.tcrm.admin.shared.BusinessException {@code SHOP_NOT_FOUND} or {@code SHOP_DISABLED}
   */
  void requireEnabled(long shopId);

  /** Names of non-deleted shops visible in the caller's company; missing ids are omitted. */
  Map<Long, String> namesByIds(Collection<Long> shopIds);
}
