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

  /** Validates channel, current eligibility, and requested quantity without reserving capacity. */
  void validateCopyTarget(long shopId, String channel, int quantity);

  /** Revalidates shop eligibility before each external copy operation. */
  void requireCopyEligible(long shopId, String channel);

  /** Locks and chooses the eligible shop with the greatest unreserved capacity. */
  long allocateCopyTarget(String channel, int quantity);

  /** Lists target channels and their unreserved capacity for token creation. */
  java.util.List<CopyChannelDTO> copyChannels();

  /** Names of non-deleted shops visible in the caller's company; missing ids are omitted. */
  Map<Long, String> namesByIds(Collection<Long> shopIds);

  /** Company-scoped immutable shop codes. */
  Map<Long, String> codesByIds(Collection<Long> shopIds);

  record CopyChannelDTO(String channel, int remainingCapacity, java.util.List<CopyShopDTO> shops) {}

  record CopyShopDTO(Long id, String shopCode, String shopName, int remainingCapacity) {}
}
