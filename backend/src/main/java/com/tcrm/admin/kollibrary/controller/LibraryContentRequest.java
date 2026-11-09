package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryContentCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One content record's manual fields (requirement 1.2.3). The calculated fields take no input: the
 * service derives them, so the screen and the API can never disagree about the formula.
 */
public record LibraryContentRequest(
    @Size(max = 512) String publishUrl,
    @NotNull LocalDate publishDate,
    @Size(max = 16) String contentForm,
    Long clicks,
    Long orders,
    BigDecimal gmv,
    @Size(max = 16) String sampleType,
    BigDecimal sampleCost,
    BigDecimal slotFee,
    BigDecimal commissionRate,
    @Size(max = 1000) String nextAction) {
  public LibraryContentCommand toCommand() {
    return new LibraryContentCommand(
        publishUrl,
        publishDate,
        contentForm,
        clicks,
        orders,
        gmv,
        sampleType,
        sampleCost,
        slotFee,
        commissionRate,
        nextAction);
  }
}
