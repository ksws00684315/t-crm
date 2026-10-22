package com.tcrm.admin.product.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Creator-item detail (task 39). {@code totalPayAmount} uses the same paid-order rule as the list.
 */
public record KolItemDetail(
    KolItemView item,
    String sourceItemUrl,
    long visitCount,
    BigDecimal totalPayAmount,
    BigDecimal refundAmount,
    Instant cleanupTime,
    String cleanupReason,
    String cleanupFailReason) {}
