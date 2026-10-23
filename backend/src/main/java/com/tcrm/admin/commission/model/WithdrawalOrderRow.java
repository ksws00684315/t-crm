package com.tcrm.admin.commission.model;

import java.math.BigDecimal;

/** One historical order allocation, never reconstructed from today's wallet. */
public record WithdrawalOrderRow(
    Long id,
    String orderNo,
    String productsJson,
    BigDecimal occupied,
    BigDecimal remainingSnapshot) {}
