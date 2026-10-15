package com.tcrm.admin.integration.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One parsed ECB feed: its reference date and USD per 1 CNY (8 decimals). */
public record EcbRate(LocalDate date, BigDecimal cnyToUsd) {}
