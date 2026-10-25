package com.tcrm.admin.kol.api;

import java.math.BigDecimal;

/**
 * Promotion-config default rate effective at an order's pay time (task 62 / ADR-032 §3).
 *
 * @param rate {@code after_json.defaultRate} of the latest CONFIG record with {@code effective_time
 *     <= pay_time}; null when the config record carries no parsable rate
 * @param versionNo {@code version_no} of that record; null when no record is effective
 */
public record KolPayTimeRateDTO(BigDecimal rate, Integer versionNo) {}
