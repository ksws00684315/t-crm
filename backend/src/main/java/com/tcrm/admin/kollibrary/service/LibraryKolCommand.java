package com.tcrm.admin.kollibrary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Create or edit a library profile (requirement 1.2.1, 1.2.2, 1.2.6 ①). The follow-up fields are
 * also updated by their own dialog; this command carries the whole profile because the screen edits
 * all six groups at once (requirement 1.2.5 ④).
 */
public record LibraryKolCommand(
    String platform,
    String kolUsername,
    String displayName,
    Long followers,
    String countryCode,
    String profileUrl,
    List<String> categories,
    Long avgViews,
    BigDecimal engagementRate,
    String audienceCountry,
    String audienceAgeRange,
    String audienceGender,
    String contact,
    String contentQuality,
    String salesPotential,
    String cooperationAttitude,
    String grade,
    String cooperationStage,
    List<String> tags,
    String remark,
    String outreachStatus,
    String followUpOwner,
    LocalDate lastContactDate,
    String sampleStatus,
    String trackingNo,
    LocalDate sampleDate) {}
