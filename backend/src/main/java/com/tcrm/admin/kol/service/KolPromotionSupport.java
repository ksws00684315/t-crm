package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared promotion rules (ADR-023): display status, config completeness, JSON snapshots. */
final class KolPromotionSupport {
  private KolPromotionSupport() {}

  static String displayStatus(String promotionStatus, BdUserDTO bd) {
    if (bd == null || !bd.usable()) {
      return PromotionStatus.DISPLAY_PENDING_UPDATE;
    }
    return promotionStatus;
  }

  static boolean configComplete(KolDO kol, List<Long> platformTemplateIds) {
    return kol.getDefaultRate() != null
        && platformTemplateIds != null
        && !platformTemplateIds.isEmpty()
        && kol.getLandingUrl() != null
        && !kol.getLandingUrl().isBlank()
        && kol.getInviteCode() != null
        && !kol.getInviteCode().isBlank();
  }

  static Map<String, Object> snapshot(KolDO kol, List<Long> platformTemplateIds) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("promotionStatus", kol.getPromotionStatus());
    map.put("defaultRate", rate(kol.getDefaultRate()));
    map.put("platformTemplateIds", platformTemplateIds == null ? List.of() : platformTemplateIds);
    map.put("landingUrl", kol.getLandingUrl());
    map.put("inviteCode", kol.getInviteCode());
    map.put("sheetUrl", kol.getSheetUrl());
    return map;
  }

  private static String rate(BigDecimal rate) {
    return rate == null ? null : KolRules.rateText(rate);
  }
}
