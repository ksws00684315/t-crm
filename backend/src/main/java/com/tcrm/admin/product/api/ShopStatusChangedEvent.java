package com.tcrm.admin.product.api;

/**
 * Published inside the transaction that enables or disables a shop (task 06 §4.3).
 *
 * @param shopId shop ID
 */
public record ShopStatusChangedEvent(Long shopId) {}
