package com.tcrm.admin.stats.controller;

import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Management overview query; both dates are optional as a pair. */
public record StatsOverviewQuery(
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
    @Positive Long shopId,
    @Positive Long kolId,
    @Positive Long inviterId,
    String channel,
    @Size(max = 100) String creatorKeyword,
    @Size(max = 100) String inviterKeyword,
    @Size(max = 100) String productKeyword,
    Boolean affiliateOnly) {
  public StatsOverviewQuery(
      LocalDate startDate,
      LocalDate endDate,
      Long shopId,
      Long kolId,
      Long inviterId,
      String channel,
      String creatorKeyword,
      String inviterKeyword,
      String productKeyword) {
    this(
        startDate,
        endDate,
        shopId,
        kolId,
        inviterId,
        channel,
        creatorKeyword,
        inviterKeyword,
        productKeyword,
        null);
  }

  public StatsOverviewQuery(
      LocalDate startDate,
      LocalDate endDate,
      Long shopId,
      Long kolId,
      Long inviterId,
      String channel,
      String creatorKeyword,
      String inviterKeyword) {
    this(
        startDate,
        endDate,
        shopId,
        kolId,
        inviterId,
        channel,
        creatorKeyword,
        inviterKeyword,
        null);
  }

  FilterDTO toFilter() {
    return new FilterDTO(
        startDate,
        endDate,
        shopId,
        kolId,
        inviterId,
        channel,
        creatorKeyword,
        inviterKeyword,
        productKeyword,
        Boolean.TRUE.equals(affiliateOnly));
  }
}
