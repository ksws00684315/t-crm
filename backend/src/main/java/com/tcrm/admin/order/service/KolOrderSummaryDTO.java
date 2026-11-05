package com.tcrm.admin.order.service;

import java.math.BigDecimal;

/**
 * Creator order summary over the same orders the list shows for these filters (paging ignored),
 * amount-terms §5a (task 115). {@code orderSalesCny} is the pay-amount sum, freight and refunds
 * included. {@code estimatedRefundCny} adds the full pay amount of every order with an unconcluded
 * refund and the refunded amount of the concluded ones (partial refunds count what was refunded),
 * so {@code commissionBaseCny} — their difference — equals the sum of the per-order commission
 * bases (amount-terms §2.1). {@code estimatedCommissionCny} sums the rows' estimated commissions.
 */
public record KolOrderSummaryDTO(
    BigDecimal orderSalesCny,
    BigDecimal estimatedRefundCny,
    BigDecimal commissionBaseCny,
    BigDecimal estimatedCommissionCny) {}
