package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;
import java.time.Instant;

/** One creator-ledger line for paid prepays or commission deductions. */
public record KolPrepayLedgerRowVO(
    Long entryId,
    Long prepayId,
    String prepayNo,
    String entryType,
    String amountCny,
    Instant occurredAt,
    String balanceAfterCny) {
  static KolPrepayLedgerRowVO from(PrepayService.LedgerRow row) {
    if (row == null) {
      return null;
    }
    return new KolPrepayLedgerRowVO(
        row.entryId(),
        row.prepayId(),
        row.prepayNo(),
        row.entryType(),
        row.amountCny(),
        row.occurredAt(),
        row.balanceAfterCny());
  }
}
