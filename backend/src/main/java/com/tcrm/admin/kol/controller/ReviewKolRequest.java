package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewKolRequest(
    @NotBlank @Size(max = KolRules.MAX_NAME) String name,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN) String phone,
    @Size(max = KolRules.MAX_WHATSAPP) @Pattern(regexp = KolRules.WHATSAPP_PATTERN) String whatsapp,
    @Pattern(regexp = "TikTok|Instagram|YouTube|X|Facebook|Other") String primaryPlatform,
    @Size(max = KolRules.MAX_NAME) String primaryHandle,
    @CountryCode String country,
    @Size(max = KolRules.MAX_REMARK) String remark) {
  KolPromotionService.ReviewCommand toCommand() {
    return new KolPromotionService.ReviewCommand(
        name, phone, whatsapp, primaryPlatform, primaryHandle, country, remark);
  }
}
