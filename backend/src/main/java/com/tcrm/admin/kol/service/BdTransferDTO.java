package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.model.KolBdTransferDO;
import java.time.Instant;

public record BdTransferDTO(
    Long id,
    Long kolId,
    Long fromBdUserId,
    Long toBdUserId,
    Instant effectiveAt,
    BdTransferStatus status,
    Instant executedAt,
    String cancelReason,
    Instant createTime) {
  static BdTransferDTO from(KolBdTransferDO r) {
    return new BdTransferDTO(
        r.getId(),
        r.getKolId(),
        r.getFromBdUserId(),
        r.getToBdUserId(),
        r.getEffectiveAt(),
        r.getStatus(),
        r.getExecutedAt(),
        r.getCancelReason(),
        r.getCreateTime());
  }
}
