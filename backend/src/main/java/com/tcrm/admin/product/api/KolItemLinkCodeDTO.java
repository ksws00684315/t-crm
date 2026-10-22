package com.tcrm.admin.product.api;

/**
 * Link code of one creator-item mapping (task 39).
 *
 * @param kolItemId crm_kol_item.id
 * @param linkCode company-unique code {@code KPL-} plus 12 uppercase hex characters
 */
public record KolItemLinkCodeDTO(Long kolItemId, String linkCode) {}
