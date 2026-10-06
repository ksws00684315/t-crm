package com.tcrm.admin.product.api;

/**
 * Published inside the transaction that puts an original product on or off sale (task 06 §4.3).
 *
 * @param productId original product ID
 */
public record ProductStatusChangedEvent(Long productId) {}
