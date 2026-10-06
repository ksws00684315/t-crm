package com.tcrm.admin.product.api;

/**
 * A current kol item mapping with the state the link index needs (task 06 §4.3).
 *
 * @param kolItemId {@code crm_kol_item.id}
 * @param kolId KOL ID
 * @param productId original product ID
 * @param productCode original product code (field of the link index hash)
 * @param productName original product name
 * @param shopId shop of the exclusive item
 * @param weidianItemId exclusive item Weidian ID (target of the link)
 * @param productOnSale whether the original product is on sale (status 1)
 * @param shopEnabled whether the exclusive item's shop is enabled (status 1)
 */
public record KolItemLinkDTO(
    Long kolItemId,
    Long kolId,
    Long productId,
    String productCode,
    String productName,
    Long shopId,
    String weidianItemId,
    boolean productOnSale,
    boolean shopEnabled) {}
