package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import java.util.List;

public record PromotionConfigVO(
    String defaultRate,
    List<PlatformAddressVO> platforms,
    String inviteCode,
    String sheetUrl,
    Long jumpTemplateId,
    String remark) {
  static PromotionConfigVO from(KolPromotionService.PromotionConfigDTO d) {
    return new PromotionConfigVO(
        d.defaultRate(),
        d.platforms().stream()
            .map(
                p ->
                    new PlatformAddressVO(
                        p.templateId() == null ? null : Long.toString(p.templateId()),
                        null,
                        p.address()))
            .toList(),
        d.inviteCode(),
        d.sheetUrl(),
        d.jumpTemplateId(),
        d.remark());
  }

  public record PlatformAddressVO(String templateId, String templateName, String address) {}
}
