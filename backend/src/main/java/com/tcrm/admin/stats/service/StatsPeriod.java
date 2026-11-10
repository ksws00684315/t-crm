package com.tcrm.admin.stats.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Inclusive Beijing dates with half-open UTC query bounds. */
public record StatsPeriod(LocalDate startDate, LocalDate endDate) {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  public StatsPeriod {
    if (startDate == null
        || endDate == null
        || endDate.isBefore(startDate)
        || ChronoUnit.DAYS.between(startDate, endDate) >= 90
        || startDate.getYear() < 1800
        || endDate.getYear() > 9998) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  public static StatsPeriod resolve(LocalDate startDate, LocalDate endDate, LocalDate today) {
    if (startDate == null && endDate == null) {
      return new StatsPeriod(today.minusDays(6), today);
    }
    if (startDate != null && startDate.getYear() < 1900) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return new StatsPeriod(startDate, endDate);
  }

  public Instant from() {
    return startDate.atStartOfDay(ZONE).toInstant();
  }

  public Instant until() {
    return endDate.plusDays(1).atStartOfDay(ZONE).toInstant();
  }

  public StatsPeriod previous() {
    long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
    return new StatsPeriod(startDate.minusDays(days), startDate.minusDays(1));
  }
}
