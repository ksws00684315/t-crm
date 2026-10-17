package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.service.CopyTokenRowDTO;
import java.time.Instant;

/** List / create row without plaintext. */
public record CopyTokenVO(
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

  static CopyTokenVO from(CopyTokenRowDTO row) {
    return new CopyTokenVO(
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
