package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/** One paid-result or deduction line on the creator ledger. */
public record KolLedgerRow(
    long entryId,
    long prepayId,
    String prepayNo,
    String entryType,
    BigDecimal amountCny,
    Instant occurredAt,
    BigDecimal balanceAfterCny) {}
