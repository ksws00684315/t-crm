package com.tcrm.admin.kol.service;

import java.time.Instant;
import java.util.List;

public record BdTransferCommand(
    List<Long> kolIds, Long fromBdUserId, Long toBdUserId, Instant effectiveAt, String reason) {}
