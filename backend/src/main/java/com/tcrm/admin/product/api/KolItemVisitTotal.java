package com.tcrm.admin.product.api;

/**
 * Valid visits of one creator-item mapping (task 39). Bot and duplicate clicks are excluded.
 *
 * @param kolItemId crm_kol_item.id
 * @param visitCount clicks that are neither bot nor duplicate
 */
public record KolItemVisitTotal(Long kolItemId, long visitCount) {}
