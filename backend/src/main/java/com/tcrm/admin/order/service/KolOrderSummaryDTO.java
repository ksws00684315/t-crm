package com.tcrm.admin.order.service;

import java.math.BigDecimal;

/**
 * Sales summary of the creator's own paid order lines under the same filters as the order list.
 * {@code salesCny} is the pay amount before refunds; settled and pending are net of refunds; the
 * three need not add up because unverified and no-commission lines belong to neither.
 */
public record KolOrderSummaryDTO(
    BigDecimal salesCny, BigDecimal settledCny, BigDecimal pendingCny, BigDecimal refundedCny) {}
