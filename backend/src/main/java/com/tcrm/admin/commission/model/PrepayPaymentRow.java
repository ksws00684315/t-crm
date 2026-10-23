package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/** The single payment fact of a prepay, when one exists. */
public record PrepayPaymentRow(
    BigDecimal payAmountCny,
    Instant payTime,
    String payMethod,
    String payRemark,
    Long voucherFileId,
    Long operatorId,
    String operatorName,
    String operatorRole) {}
