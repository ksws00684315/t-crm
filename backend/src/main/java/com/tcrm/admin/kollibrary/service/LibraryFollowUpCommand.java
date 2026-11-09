package com.tcrm.admin.kollibrary.service;

import java.time.LocalDate;

/**
 * Follow-up dialog input (requirement 1.2.5 ⑤): it changes only the outreach status, the last
 * contact date, the sample status, the tracking number and the sample date.
 */
public record LibraryFollowUpCommand(
    String outreachStatus,
    LocalDate lastContactDate,
    String sampleStatus,
    String trackingNo,
    LocalDate sampleDate) {}
