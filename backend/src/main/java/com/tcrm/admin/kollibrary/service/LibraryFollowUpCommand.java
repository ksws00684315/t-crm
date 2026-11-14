package com.tcrm.admin.kollibrary.service;

import java.time.LocalDate;

/**
 * Follow-up dialog input (requirement 1.2.5 ⑤): it changes only the outreach status, the last
 * contact date, the sample status, the sample tracking number, the sample date and the follow-up
 * remark (2026-11-10).
 */
public record LibraryFollowUpCommand(
    String outreachStatus,
    LocalDate lastContactDate,
    String sampleStatus,
    String trackingNo,
    LocalDate sampleDate,
    String followUpRemark) {}
