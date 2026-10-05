package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.CreateKolCommand;
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
 * New KOL. {@code defaultRate} is a fraction (0.1550 = 15.5%); {@code bdUserId} is ignored for a BD
 * (OWN) caller and may be null (unassigned).
 */
public record CreateKolRequest(
    @NotBlank @Size(max = KolRules.MAX_NAME) String name,
    @NotBlank @Size(max = KolRules.MAX_EMAIL) @Pattern(regexp = KolRules.EMAIL_PATTERN)
        String email,
    @NotBlank @CountryCode String country,
    @Size(max = KolRules.MAX_SOCIAL_ACCOUNTS) List<@Valid SocialAccountRequest> socialAccounts,
    @Min(1) Long bdUserId,
    @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal defaultRate,
    @Size(max = KolRules.MAX_REMARK) String remark) {
  CreateKolCommand toCommand() {
    return new CreateKolCommand(
        name, email, country, KolRequests.social(socialAccounts), bdUserId, defaultRate, remark);
  }
}
