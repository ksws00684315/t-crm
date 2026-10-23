package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/** Existing ledger row addressed by its idempotent source key. */
public record LedgerSourceRow(
    BigDecimal amountCny,
    BigDecimal balanceAfterCny,
    long kolId,
    String sourceType,
    long sourceId) {}
