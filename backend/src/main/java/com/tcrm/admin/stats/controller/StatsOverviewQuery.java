package com.tcrm.admin.stats.controller;

import com.tcrm.admin.stats.service.StatsOverviewService.FilterDTO;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Management overview query; both dates are optional as a pair. */
public record StatsOverviewQuery(
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
    @Positive Long shopId,
    @Positive Long kolId,
    @Positive Long inviterId,
    String channel) {
  FilterDTO toFilter() {
    return new FilterDTO(startDate, endDate, shopId, kolId, inviterId, channel);
  }
}
