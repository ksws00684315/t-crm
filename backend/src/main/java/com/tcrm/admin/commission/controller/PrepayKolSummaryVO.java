package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;

/** Paid prepay total for one creator, used by the KOL list badge. */
public record PrepayKolSummaryVO(Long kolId, String paidAmountCny) {
  static PrepayKolSummaryVO from(PrepayService.KolSummary summary) {
    if (summary == null) {
      return null;
    }
    return new PrepayKolSummaryVO(summary.kolId(), summary.paidAmountCny());
  }
}
