package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PromotionConfigRequest(
    @Size(max = 16) String defaultRate,
    List<Long> platformTemplateIds,
    @Size(max = KolRules.MAX_URL) @Pattern(regexp = KolRules.HTTPS_URL_PATTERN) String landingUrl,
    @Size(max = KolRules.MAX_INVITE_CODE) @Pattern(regexp = KolRules.INVITE_CODE_PATTERN)
        String inviteCode,
    @Size(max = KolRules.MAX_SHEET_URL) @Pattern(regexp = KolRules.SHEET_URL_PATTERN)
        String sheetUrl,
    @Min(1) Long jumpTemplateId,
    @Size(max = KolRules.MAX_REMARK) String remark) {
  KolPromotionService.PromotionConfigCommand toCommand() {
    return new KolPromotionService.PromotionConfigCommand(
        defaultRate, platformTemplateIds, landingUrl, inviteCode, sheetUrl, jumpTemplateId, remark);
  }
}
