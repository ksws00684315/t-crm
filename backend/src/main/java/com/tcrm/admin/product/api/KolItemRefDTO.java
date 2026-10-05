package com.tcrm.admin.product.api;

/**
 * Identity of a kol item mapping, current or closed (task 06 §4.5 click attribution).
 *
 * @param kolItemId {@code crm_kol_item.id}
 * @param kolId KOL ID
 * @param productId original product ID
 */
public record KolItemRefDTO(Long kolItemId, Long kolId, Long productId) {}
