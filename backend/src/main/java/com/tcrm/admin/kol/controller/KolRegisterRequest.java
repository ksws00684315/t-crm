package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRegistration;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration body (task 12c §4.3). Password rules match task 12a. {@code primaryHandle} is
 * capped at the KOL name length because a blank name is stored as the handle. {@code primaryUrl},
 * {@code name} and {@code country} may be omitted.
 */
public record KolRegisterRequest(
    @NotBlank @Pattern(regexp = "[0-9A-Za-z]{32}") String code,
    @NotBlank @Size(max = KolRules.MAX_EMAIL) @Pattern(regexp = KolRules.EMAIL_PATTERN)
        String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String emailCode,
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password,
    @NotBlank @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN)
        String whatsapp,
    @NotBlank @Pattern(regexp = "TikTok|Instagram|YouTube|X|Facebook|Other") String primaryPlatform,
    @NotBlank @Size(max = KolRules.MAX_NAME) String primaryHandle,
    @Size(max = KolRules.MAX_URL) @Pattern(regexp = KolRules.HTTPS_URL_PATTERN) String primaryUrl,
    @Size(max = KolRules.MAX_NAME) String name,
    @CountryCode String country) {
  KolRegistration toRegistration() {
    return new KolRegistration(
        code,
        email,
        emailCode,
        password,
        whatsapp,
        primaryPlatform,
        primaryHandle,
        primaryUrl,
        name,
        country);
  }
}
