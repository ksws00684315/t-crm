package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.service.CreatedCopyTokenDTO;
import java.time.Instant;

/** Create response: plaintext once, plus list-row fields. */
public record CreatedCopyTokenVO(
    String token,
    Long id,
    String tokenHint,
    Long kolId,
    String kolCode,
    String kolName,
    String targetChannel,
    Long targetShopId,
    String targetShopName,
    int used,
    int quota,
    int validMinutes,
    Instant expiresAt,
    CopyTokenDisplayStatus displayStatus,
    Instant lastUsedAt,
    Instant createTime) {

  static CreatedCopyTokenVO from(CreatedCopyTokenDTO dto) {
    var row = dto.row();
    return new CreatedCopyTokenVO(
        dto.token(),
        row.id(),
        row.tokenHint(),
        row.kolId(),
        row.kolCode(),
        row.kolName(),
        row.targetChannel(),
        row.targetShopId(),
        row.targetShopName(),
        row.used(),
        row.quota(),
        row.validMinutes(),
        row.expiresAt(),
        row.displayStatus(),
        row.lastUsedAt(),
        row.createTime());
  }
}
