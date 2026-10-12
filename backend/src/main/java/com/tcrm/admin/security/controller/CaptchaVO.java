package com.tcrm.admin.security.controller;

import com.tcrm.admin.security.service.CaptchaService;

/**
 * Login captcha. {@code image} is a PNG data URL; {@code expiresIn} is in seconds. When captchas
 * are disabled only {@code enabled = false} is set.
 */
public record CaptchaVO(boolean enabled, String captchaId, String image, long expiresIn) {
  static CaptchaVO from(CaptchaService.IssuedCaptcha c) {
    return new CaptchaVO(c.enabled(), c.captchaId(), c.image(), c.expiresIn());
  }
}
