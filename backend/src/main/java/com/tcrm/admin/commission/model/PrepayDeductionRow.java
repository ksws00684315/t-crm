package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/** Posted payment amount and the deduction ledger it created. */
public record PrepayDeductionRow(BigDecimal posted, long ledgerId) {}
