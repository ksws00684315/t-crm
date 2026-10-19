package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRegistration;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration body (task 29). Password rules match task 12a. WhatsApp and primary social are
 * optional; platform and handle must both be set or both omitted.
 */
public record KolRegisterRequest(
    @NotBlank @Pattern(regexp = "[2-9A-HJ-NP-Za-hj-np-z]{8}") String code,
    @NotBlank @Size(max = KolRules.MAX_EMAIL) @Pattern(regexp = KolRules.EMAIL_PATTERN)
        String email,
    @NotBlank @Pattern(regexp = "\\d{6}") String emailCode,
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password,
    @NotBlank @Size(max = KolRules.MAX_NAME) String name,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN) String phone,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN) String whatsapp,
    @Pattern(regexp = "TikTok|Instagram|YouTube|X|Facebook|Other") String primaryPlatform,
    @Size(max = KolRules.MAX_NAME) String primaryHandle,
    @Size(max = KolRules.MAX_URL) @Pattern(regexp = KolRules.HTTPS_URL_PATTERN) String primaryUrl,
    @CountryCode String country) {
  KolRegistration toRegistration() {
    return new KolRegistration(
        code,
        email,
        emailCode,
        password,
        name,
        phone,
        whatsapp,
        primaryPlatform,
        primaryHandle,
        primaryUrl,
        country);
  }
}
