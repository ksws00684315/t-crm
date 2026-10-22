package com.tcrm.admin.system.controller;

import com.tcrm.admin.system.model.DeparturePlanType;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Administrator's plan: type, effective time and optional heir. A missing {@code effectiveAt} runs
 * the plan immediately.
 */
public record DeparturePlanRequest(
    DeparturePlanType type, Instant effectiveAt, Long heirUserId, @Size(max = 255) String reason) {}
