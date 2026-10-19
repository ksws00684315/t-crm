package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.EditKolCommand;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Ordinary edit body (task 28 §3.2 / §3.3): profile fields only. Email is shown read-only in the
 * admin UI and is never accepted here; promotion fields go through promotion-config.
 */
public record EditKolRequest(
    @NotBlank @Size(max = KolRules.MAX_NAME) String name,
    @CountryCode String country,
    @Size(max = KolRules.MAX_SOCIAL_ACCOUNTS) List<@Valid SocialAccountRequest> socialAccounts,
    @Size(max = KolRules.MAX_REMARK) String remark,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN)
        String whatsapp) {
  EditKolCommand toCommand() {
    return new EditKolCommand(
        name, country, KolRequests.social(socialAccounts), remark, KolRequests.text(whatsapp));
  }
}
