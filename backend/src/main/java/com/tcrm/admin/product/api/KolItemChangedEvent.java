package com.tcrm.admin.product.api;

/**
 * Published inside the transaction that adds, replaces or closes a kol item mapping (task 06 §4.3).
 * Listeners should react after commit and look up the current mapping of the pair themselves.
 *
 * @param kolId KOL ID
 * @param productId original product ID
 */
public record KolItemChangedEvent(Long kolId, Long productId) {}
