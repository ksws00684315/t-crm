package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.BdTransferStatus;
import java.time.Instant;

/**
 * Transfer row plus the display fields resolved per page (task 16b): creator code/name and the
 * from/to BD display names (null means unassigned).
 */
public record BdTransferDTO(
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
    Instant createTime) {}
