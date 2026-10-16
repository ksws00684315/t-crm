package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.EditKolCommand;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Editable KOL fields; the code and the BD cannot be changed here. The 2026-10-16 profile fields
 * (task 11 §4.3) behave as in {@link CreateKolRequest}; a null clears them.
 */
public record EditKolRequest(
    @NotBlank @Size(max = KolRules.MAX_NAME) String name,
    @NotBlank @Size(max = KolRules.MAX_EMAIL) @Pattern(regexp = KolRules.EMAIL_PATTERN)
        String email,
    @CountryCode String country,
    @Size(max = KolRules.MAX_SOCIAL_ACCOUNTS) List<@Valid SocialAccountRequest> socialAccounts,
    @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal defaultRate,
    @Size(max = KolRules.MAX_REMARK) String remark,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN) String whatsapp,
    @Min(1) Long linkTemplateId,
    @Size(max = KolRules.MAX_INVITE_CODE) @Pattern(regexp = KolRules.INVITE_CODE_PATTERN)
        String inviteCode,
    @Size(max = KolRules.MAX_SHEET_URL) @Pattern(regexp = KolRules.SHEET_URL_PATTERN)
        String sheetUrl) {
  EditKolCommand toCommand() {
    return new EditKolCommand(
        name,
        email,
        country,
        KolRequests.social(socialAccounts),
        defaultRate,
        remark,
        KolRequests.text(whatsapp),
        linkTemplateId,
        KolRequests.text(inviteCode),
        KolRequests.text(sheetUrl));
  }
}
