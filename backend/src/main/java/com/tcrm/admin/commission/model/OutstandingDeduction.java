package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/** Unrecovered remainder of one prepay deduction, oldest first. */
public record OutstandingDeduction(long ledgerId, BigDecimal remaining) {}
