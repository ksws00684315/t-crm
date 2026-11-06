package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.Size;

/**
 * Whitelisted self-service profile fields; the nickname, account and company identifiers are
 * session-owned and read-only (the nickname is fixed at registration, task 122).
 */
public record KolMeUpdateRequest(
    @Size(max = KolRules.MAX_PLATFORM) String primaryPlatform,
    @Size(max = KolRules.MAX_HANDLE) String primaryHandle) {}
