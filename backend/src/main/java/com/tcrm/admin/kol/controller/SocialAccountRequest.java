package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolRules;
import com.tcrm.admin.kol.service.SocialAccountDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One social account; {@code url} must be https when given, {@code followers} is optional. */
public record SocialAccountRequest(
    @NotBlank @Size(max = KolRules.MAX_PLATFORM) String platform,
    @NotBlank @Size(max = KolRules.MAX_HANDLE) String handle,
    @Size(max = KolRules.MAX_URL) @Pattern(regexp = KolRules.HTTPS_URL_PATTERN) String url,
    @Min(0) @Max(999_999_999_999_999L) Long followers) {
  SocialAccountDTO toDto() {
    return new SocialAccountDTO(
        platform.strip(), handle.strip(), url == null || url.isBlank() ? null : url, followers);
  }
}
