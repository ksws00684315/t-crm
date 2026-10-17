package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import java.time.Instant;

/** One list row; never carries plaintext. */
public record CopyTokenRowDTO(
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
    Instant createTime) {}
