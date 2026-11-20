package com.tcrm.admin.kollibrary.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create or edit one content record (requirement 1.2.3 manual fields). The calculated values are
 * never part of the input.
 */
public record LibraryContentCommand(
    String publishUrl,
    String publishTitle,
    LocalDate publishDate,
    String contentForm,
    String sampleStatus,
    BigDecimal sampleCost,
    BigDecimal slotFee,
    BigDecimal commissionRate,
    String nextAction,
    String remark) {}
