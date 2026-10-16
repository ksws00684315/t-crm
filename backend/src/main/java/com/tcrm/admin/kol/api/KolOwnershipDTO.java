package com.tcrm.admin.kol.api;

import java.time.Instant;

/** BD interval from the current company; end and BD may be null. */
public record KolOwnershipDTO(Long kolId, Long bdUserId, Instant startTime, Instant endTime) {}
