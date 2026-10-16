package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /kol-api/register/email-codes}. */
public record KolRegisterEmailRequest(
    @NotBlank @Pattern(regexp = "[0-9A-Za-z]{32}") String code,
    @NotBlank @Size(max = KolRules.MAX_EMAIL) @Pattern(regexp = KolRules.EMAIL_PATTERN)
        String email) {}
