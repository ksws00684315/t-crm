package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.service.BdTransferDTO;
import java.time.Instant;

public record BdTransferVO(
    Long id,
    Long kolId,
    Long fromBdUserId,
    Long toBdUserId,
    Instant effectiveAt,
    BdTransferStatus status,
    Instant executedAt,
    String cancelReason,
    Instant createTime) {
  static BdTransferVO from(BdTransferDTO d) {
    return new BdTransferVO(
        d.id(),
        d.kolId(),
        d.fromBdUserId(),
        d.toBdUserId(),
        d.effectiveAt(),
        d.status(),
        d.executedAt(),
        d.cancelReason(),
        d.createTime());
  }
}
