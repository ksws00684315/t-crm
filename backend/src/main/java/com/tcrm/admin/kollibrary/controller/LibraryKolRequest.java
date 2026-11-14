package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryKolCommand;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Profile form (requirement 1.2.1, 1.2.2, 1.2.6 ①). Field lengths are the stored maximums; the
 * creator ID is normalised and the choices, amounts and percentages are checked in the service,
 * where the registry of values lives.
 */
public record LibraryKolRequest(
    @Size(max = 16) String platform,
    @Size(max = 64) String kolUsername,
    @Size(max = 100) String displayName,
    Long followers,
    @Size(max = 8) String countryCode,
    @Size(max = 512) String profileUrl,
    List<@Size(max = 48) String> categories,
    Long avgViews,
    BigDecimal engagementRate,
    @Size(max = 64) String audienceCountry,
    @Size(max = 16) String audienceAgeRange,
    @Size(max = 16) String audienceGender,
    @Size(max = 128) String contact,
    @Size(max = 8) String contentQuality,
    @Size(max = 8) String salesPotential,
    @Size(max = 8) String cooperationAttitude,
    @Size(max = 4) String grade,
    List<@Size(max = 20) String> tags,
    @Size(max = 1000) String remark,
    @Size(max = 16) String outreachStatus,
    @Size(max = 64) String followUpOwner,
    LocalDate lastContactDate,
    @Size(max = 16) String sampleStatus,
    @Size(max = 64) String trackingNo,
    LocalDate sampleDate,
    @Size(max = 1000) String followUpRemark) {

  public LibraryKolCommand toCommand() {
    return new LibraryKolCommand(
        platform,
        kolUsername,
        displayName,
        followers,
        countryCode,
        profileUrl,
        categories,
        avgViews,
        engagementRate,
        audienceCountry,
        audienceAgeRange,
        audienceGender,
        contact,
        contentQuality,
        salesPotential,
        cooperationAttitude,
        grade,
        tags,
        remark,
        outreachStatus,
        followUpOwner,
        lastContactDate,
        sampleStatus,
        trackingNo,
        sampleDate,
        followUpRemark);
  }
}
