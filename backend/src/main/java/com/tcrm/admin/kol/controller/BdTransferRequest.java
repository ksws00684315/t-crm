package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.BdTransferCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Exactly one of kolIds (1..500) and fromBdUserId is required. Null target means company-owned. */
public record BdTransferRequest(
    @Size(min = 1, max = 500) List<@NotNull @Min(1) Long> kolIds,
    @Min(1) Long fromBdUserId,
    @Min(1) Long toBdUserId,
    Instant effectiveAt,
    @Size(max = 255) String reason) {
  BdTransferCommand toCommand() {
    return new BdTransferCommand(kolIds, fromBdUserId, toBdUserId, effectiveAt, reason);
  }
}
