package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/** Sum of paid amounts for one creator. */
public record PrepayPaidSum(Long kolId, BigDecimal paidAmountCny) {}
