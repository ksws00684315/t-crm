package com.tcrm.admin.product.api;

import java.time.Instant;
import java.util.List;

/** Historical mapping lookup for order attribution within the current company. */
public interface OrderMappingApi {
  /**
   * Returns up to two mappings valid at paidAt, or an empty list. Two candidates signal overlap;
   * callers must leave the item unmatched and may log their order context.
   */
  List<KolItemRefDTO> findAt(Long shopId, String itemId, Instant paidAt);
}
