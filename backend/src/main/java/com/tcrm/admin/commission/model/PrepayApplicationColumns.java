package com.tcrm.admin.commission.model;

import java.time.LocalDate;

/**
 * Applicant-supplied fields stored on the prepay row. {@code id} is selected only so MyBatis still
 * builds a row when every applicant field is null (a new application).
 */
public record PrepayApplicationColumns(
    Long id,
    String reason,
    LocalDate expectedPayDate,
    Boolean deductFromCommission,
    Long payoutAccountId,
    String payoutAccountType,
    String payoutCurrency,
    String payoutNetwork,
    String payoutHolderName,
    String payoutAccountMasked) {}
