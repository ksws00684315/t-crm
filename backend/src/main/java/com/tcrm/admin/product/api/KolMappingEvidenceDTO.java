package com.tcrm.admin.product.api;

import java.time.Instant;

/**
 * One kol item mapping (current or closed) with the fields order evidence needs (task 40 §3.6).
 *
 * @param kolItemId {@code crm_kol_item.id}
 * @param kolId KOL ID
 * @param productId original product ID
 * @param productCode original product code
 * @param productName original product name
 * @param shopId shop of the exclusive item
 * @param weidianItemId exclusive item Weidian ID
 * @param validFrom mapping validity start (inclusive)
 * @param validTo mapping validity end (exclusive); null while the mapping is current
 */
public record KolMappingEvidenceDTO(
    Long kolItemId,
    Long kolId,
    Long productId,
    String productCode,
    String productName,
    Long shopId,
    String weidianItemId,
    Instant validFrom,
    Instant validTo) {}
