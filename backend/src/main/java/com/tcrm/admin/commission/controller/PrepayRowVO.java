package com.tcrm.admin.commission.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tcrm.admin.commission.service.PrepayService;
import java.time.Instant;

/** Admin list row for a prepay application. */
public record PrepayRowVO(
    Long id,
    String prepayNo,
    String financeTaskType,
    Long financeTaskId,
    Long kolId,
    String kolCode,
    String kolName,
    Long applicantId,
    String applicantName,
    String amountCny,
    String currency,
    String status,
    Instant createdAt) {
  @JsonProperty("applicantRole")
  public String applicantRole() {
    return "BUSINESS";
  }

  static PrepayRowVO from(PrepayService.PrepayRow row) {
    if (row == null) {
      return null;
    }
    return new PrepayRowVO(
        row.id(),
        row.prepayNo(),
        row.financeTaskType(),
        row.financeTaskId(),
        row.kolId(),
        row.kolCode(),
        row.kolName(),
        row.applicantId(),
        row.applicantName(),
        row.amountCny(),
        row.currency(),
        row.status(),
        row.createdAt());
  }
}
