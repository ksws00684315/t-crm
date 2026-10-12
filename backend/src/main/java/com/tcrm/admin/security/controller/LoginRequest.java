package com.tcrm.admin.security.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Captcha fields are not bean-validated: a missing or malformed captcha answers
 * AUTH_CAPTCHA_INVALID like a wrong one, and they are ignored when captchas are disabled.
 */
public record LoginRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
    @NotBlank @Size(max = 72) String password,
    @Size(max = 64) String captchaId,
    @Size(max = 16) String captchaCode)
    implements com.tcrm.admin.security.api.LoginSubject {}
