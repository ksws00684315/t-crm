package com.tcrm.admin.order.service;

import java.time.Instant;
import java.util.List;

/**
 * Match evidence of one unmatched order line (task 40 §3.6, row 10.01): why the line sits in the
 * manual pool, the mapping candidates with their validity, and what was done manually.
 */
public record UnmatchedEvidenceDTO(
    Long orderItemId,
    Long orderId,
    String orderNo,
    Long shopId,
    String shopName,
    String merchantNo,
    String weidianItemId,
    String itemName,
    String skuName,
    Instant payTime,
    String orderAttributionStatus,
    String lineAttributionType,
    String unmatchedReason,
    ProductRef product,
    List<MappingDTO> mappings,
    ManualActionDTO manual) {

  /** The original product the mapping candidates point at; null when there is no mapping. */
  record ProductRef(Long productId, String productCode, String productName) {}

  /**
   * One mapping candidate of this shop's Weidian item.
   *
   * @param coversPayTime whether {@code validFrom <= payTime < validTo} (null end = current)
   */
  record MappingDTO(
      Long kolItemId,
      Long kolId,
      String kolCode,
      String kolName,
      String linkCode,
      Instant validFrom,
      Instant validTo,
      boolean coversPayTime) {}

  /** The recorded manual decision on this line, if any. */
  record ManualActionDTO(Integer attributionType, String manualReason, Instant attributionTime) {}
}
