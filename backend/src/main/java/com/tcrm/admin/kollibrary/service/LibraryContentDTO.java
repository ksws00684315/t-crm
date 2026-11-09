package com.tcrm.admin.kollibrary.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One content record of a creator with its calculated values (requirement 1.2.3, 1.2.6 ③). */
public record LibraryContentDTO(
    Long id,
    String publishUrl,
    LocalDate publishDate,
    String contentForm,
    Long clicks,
    Long orders,
    BigDecimal gmv,
    String sampleType,
    BigDecimal sampleCost,
    BigDecimal slotFee,
    BigDecimal commissionRate,
    BigDecimal estimatedCommission,
    BigDecimal totalCost,
    BigDecimal actualRoi,
    String nextAction,
    Instant updateTime) {}
