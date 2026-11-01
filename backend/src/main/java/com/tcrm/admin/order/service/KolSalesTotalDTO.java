package com.tcrm.admin.order.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Creator workbench cumulative sales: the pay amount of the creator's own lines on attributed, paid
 * orders, before refunds. Not limited by the 366-day range of the overview.
 */
public record KolSalesTotalDTO(BigDecimal salesCny, Instant asOf) {}
