package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryFollowUpCommand;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Follow-up dialog (requirement 1.2.5 ⑤). Only these five fields are accepted, so a request cannot
 * change anything else of the profile.
 */
public record LibraryFollowUpRequest(
    @Size(max = 16) String outreachStatus,
    LocalDate lastContactDate,
    @Size(max = 16) String sampleStatus,
    @Size(max = 64) String trackingNo,
    LocalDate sampleDate) {
  public LibraryFollowUpCommand toCommand() {
    return new LibraryFollowUpCommand(
        outreachStatus, lastContactDate, sampleStatus, trackingNo, sampleDate);
  }
}
