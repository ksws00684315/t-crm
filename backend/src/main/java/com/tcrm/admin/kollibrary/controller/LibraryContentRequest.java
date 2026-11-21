package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryContentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One content record's manual fields (requirement 1.2.3). The calculated fields take no input: the
 * service derives them, so the screen and the API can never disagree about the formula.
 */
public record LibraryContentRequest(
    @NotBlank @Size(max = 512) String publishUrl,
    @NotBlank @Size(max = 200) String publishTitle,
    @NotNull LocalDate publishDate,
    @NotBlank @Size(max = 16) String contentForm,
    @Size(max = 16) String sampleStatus,
    BigDecimal sampleCost,
    BigDecimal slotFee,
    BigDecimal commissionRate,
    @Size(max = 1000) String nextAction,
    @Size(max = 1000) String remark) {
  public LibraryContentCommand toCommand() {
    return new LibraryContentCommand(
        publishUrl,
        publishTitle,
        publishDate,
        contentForm,
        sampleStatus,
        sampleCost,
        slotFee,
        commissionRate,
        nextAction,
        remark);
  }
}
