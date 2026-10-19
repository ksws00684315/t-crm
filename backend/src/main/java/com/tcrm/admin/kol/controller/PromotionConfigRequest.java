package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import com.tcrm.admin.kol.service.KolPromotionService.PlatformInput;
import com.tcrm.admin.kol.service.KolRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PromotionConfigRequest(
    @Size(max = 16) String defaultRate,
    List<@Valid PlatformAddressRequest> platforms,
    @Size(max = KolRules.MAX_INVITE_CODE) @Pattern(regexp = KolRules.INVITE_CODE_PATTERN)
        String inviteCode,
    @Size(max = KolRules.MAX_SHEET_URL) @Pattern(regexp = KolRules.SHEET_URL_PATTERN)
        String sheetUrl,
    @Min(1) Long jumpTemplateId,
    @Size(max = KolRules.MAX_REMARK) String remark) {
  KolPromotionService.PromotionConfigCommand toCommand() {
    List<PlatformInput> platformInputs =
        platforms == null
            ? List.of()
            : platforms.stream()
                .map(p -> new PlatformInput(parseId(p.templateId()), p.address()))
                .toList();
    return new KolPromotionService.PromotionConfigCommand(
        defaultRate, platformInputs, inviteCode, sheetUrl, jumpTemplateId, remark);
  }

  private static Long parseId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(raw.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public record PlatformAddressRequest(
      @Size(max = 32) String templateId,
      @Size(max = KolRules.MAX_URL) @Pattern(regexp = KolRules.HTTPS_URL_PATTERN) String address) {}
}
