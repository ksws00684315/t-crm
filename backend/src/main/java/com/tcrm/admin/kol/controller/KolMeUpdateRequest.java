package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Whitelisted self-service profile fields; account and company identifiers are session-owned. */
public record KolMeUpdateRequest(
    @NotBlank @Size(max = 64) String name,
    @Size(max = KolRules.MAX_PLATFORM) String primaryPlatform,
    @Size(max = KolRules.MAX_HANDLE) String primaryHandle) {}
