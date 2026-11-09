package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryContentDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One content record; the last three amounts are calculated and read-only (requirement 1.2.6 ③).
 */
public record LibraryContentVO(
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
    Instant updateTime) {

  public static LibraryContentVO from(LibraryContentDTO dto) {
    return new LibraryContentVO(
        dto.id(),
        dto.publishUrl(),
        dto.publishDate(),
        dto.contentForm(),
        dto.clicks(),
        dto.orders(),
        dto.gmv(),
        dto.sampleType(),
        dto.sampleCost(),
        dto.slotFee(),
        dto.commissionRate(),
        dto.estimatedCommission(),
        dto.totalCost(),
        dto.actualRoi(),
        dto.nextAction(),
        dto.updateTime());
  }
}
