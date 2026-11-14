package com.tcrm.admin.kollibrary.controller;

import com.tcrm.admin.kollibrary.service.LibraryKolDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Full profile and totals of the detail page (requirements 1.2.5 ③, 1.2.6 ③). */
public record LibraryKolDetailVO(
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
    TotalsVO totals) {

  /**
   * Creator totals; {@code roi} is null when the summed total cost is 0 (shown as a dash) and is
   * the total GMV over the total cost, never an average of the rows.
   */
  public record TotalsVO(
      long contentCount,
      long clicks,
      long orders,
      BigDecimal gmv,
      BigDecimal totalCost,
      BigDecimal roi) {}

  public static LibraryKolDetailVO from(LibraryKolDTO.Detail dto) {
    LibraryKolDTO.Totals t = dto.totals();
    return new LibraryKolDetailVO(
        dto.id(),
        dto.platform(),
        dto.kolUsername(),
        dto.displayName(),
        dto.followers(),
        dto.countryCode(),
        dto.profileUrl(),
        dto.categories(),
        dto.avgViews(),
        dto.engagementRate(),
        dto.audienceCountry(),
        dto.audienceAgeRange(),
        dto.audienceGender(),
        dto.contact(),
        dto.contentQuality(),
        dto.salesPotential(),
        dto.cooperationAttitude(),
        dto.grade(),
        dto.tags(),
        dto.remark(),
        dto.outreachStatus(),
        dto.followUpOwner(),
        dto.lastContactDate(),
        dto.sampleStatus(),
        dto.trackingNo(),
        dto.sampleDate(),
        dto.followUpRemark(),
        dto.createTime(),
        dto.updateTime(),
        new TotalsVO(t.contentCount(), t.clicks(), t.orders(), t.gmv(), t.totalCost(), t.roi()));
  }
}
