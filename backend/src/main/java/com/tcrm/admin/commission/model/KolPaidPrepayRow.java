package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Paid prepay projected for the creator, including wallet and deduction ids. */
public record KolPaidPrepayRow(
    long id,
    String prepayNo,
    Boolean deductFromCommission,
    BigDecimal payAmountCny,
    Instant payTime,
    String payMethod,
    long voucherFileId,
    BigDecimal balanceCny,
    Long deductionId) {}
