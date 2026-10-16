package com.tcrm.admin.product.api;

import java.time.Instant;

/** Historical mapping lookup for order attribution within the current company. */
public interface OrderMappingApi {
  /**
   * Returns the mapping valid at paidAt, or null; overlapping mappings fail rather than choose a
   * KOL.
   */
  KolItemRefDTO findAt(Long shopId, String itemId, Instant paidAt);
}
