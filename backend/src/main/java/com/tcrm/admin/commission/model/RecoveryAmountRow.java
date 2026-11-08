package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/**
 * One recovery row (or a net per deduction) between a commission credit and a prepay deduction:
 * {@code amountCny} is positive for an offset, negative for a clawback reversal (task 141).
 */
public record RecoveryAmountRow(long deductionLedgerId, BigDecimal amountCny) {}
