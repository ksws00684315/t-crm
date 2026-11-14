package com.tcrm.admin.kollibrary.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A library creator as the list shows it (requirement 1.2.5 ②): the basic data and status of the
 * list columns only (2026-11-10), no content counts or totals.
 */
public record LibraryKolDTO(
    Long id,
    String platform,
    String kolUsername,
    String displayName,
    Long followers,
    String countryCode,
    List<String> categories,
    String grade,
    String outreachStatus,
    String followUpOwner) {

  /** Creator totals of the detail page (requirement 1.2.6 ③). */
  public record Totals(
      long contentCount,
      long clicks,
      long orders,
      BigDecimal gmv,
      BigDecimal totalCost,
      BigDecimal roi) {}

  /** Full profile for the detail page and the edit form, with its totals. */
  public record Detail(
      Long id,
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
      List<String> tags,
      String remark,
      String outreachStatus,
      String followUpOwner,
      LocalDate lastContactDate,
      String sampleStatus,
      String trackingNo,
      LocalDate sampleDate,
      String followUpRemark,
      Instant createTime,
      Instant updateTime,
      Totals totals) {}
}
