package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.service.BdTransferDTO;
import java.time.Instant;

public record BdTransferVO(
    Long id,
    Long kolId,
    String kolCode,
    String kolName,
    Long fromBdUserId,
    String fromBdUserName,
    Long toBdUserId,
    String toBdUserName,
    Instant effectiveAt,
    BdTransferStatus status,
    Instant executedAt,
    String reason,
    String cancelReason,
    Instant createTime) {
  static BdTransferVO from(BdTransferDTO d) {
    return new BdTransferVO(
        d.id(),
        d.kolId(),
        d.kolCode(),
        d.kolName(),
        d.fromBdUserId(),
        d.fromBdUserName(),
        d.toBdUserId(),
        d.toBdUserName(),
        d.effectiveAt(),
        d.status(),
        d.executedAt(),
        d.reason(),
        d.cancelReason(),
        d.createTime());
  }
}
