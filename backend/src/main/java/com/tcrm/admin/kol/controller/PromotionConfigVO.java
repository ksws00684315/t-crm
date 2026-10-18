package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolPromotionService;
import java.util.List;

public record PromotionConfigVO(
    String defaultRate,
    List<Long> platformTemplateIds,
    String landingUrl,
    String inviteCode,
    String sheetUrl,
    String remark) {
  static PromotionConfigVO from(KolPromotionService.PromotionConfigDTO d) {
    return new PromotionConfigVO(
        d.defaultRate(),
        d.platformTemplateIds(),
        d.landingUrl(),
        d.inviteCode(),
        d.sheetUrl(),
        d.remark());
  }
}
