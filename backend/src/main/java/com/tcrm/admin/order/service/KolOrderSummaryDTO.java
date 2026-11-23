package com.tcrm.admin.order.service;

import java.math.BigDecimal;

/**
 * Paid-order summary with verified commission bases and net accruals. The historical JSON field
 * {@code estimatedRefundCny} carries completed refunds; pending refunds only restrict use.
 */
public record KolOrderSummaryDTO(
    BigDecimal orderSalesCny,
    BigDecimal estimatedRefundCny,
    BigDecimal commissionBaseCny,
    BigDecimal estimatedCommissionCny) {}
