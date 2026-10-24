package com.tcrm.admin.product.api;

import java.math.BigDecimal;

/**
 * Product-level commission rate of an original product (task 62, design §8.3 [D7]).
 *
 * @param productId {@code crm_product.id}
 * @param rate product rate; null when the product has none
 */
public record ProductRateDTO(Long productId, BigDecimal rate) {}
