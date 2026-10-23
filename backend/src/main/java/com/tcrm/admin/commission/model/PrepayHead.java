package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Identity columns of one prepay, without joined display names. */
public record PrepayHead(
    Long id,
    String prepayNo,
    Long kolId,
    Long applicantId,
    BigDecimal amountCny,
    String status,
    Instant createTime) {}
