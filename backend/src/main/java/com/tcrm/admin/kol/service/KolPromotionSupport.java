package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.PromotionStatus;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    return missingConfigFields(kol, platformTemplateIds).isEmpty();
  }

  /**
   * Missing promotion-config field names for a PROMOTING transition. Names are fixed for the admin
   * UI: {@code defaultRate}, {@code platformTemplateIds}, {@code landingUrl}, {@code inviteCode},
   * {@code jumpTemplateId}.
   */
  static List<String> missingConfigFields(KolDO kol, List<Long> platformTemplateIds) {
    List<String> missing = new ArrayList<>(5);
    if (kol.getDefaultRate() == null) {
      missing.add("defaultRate");
    }
    if (platformTemplateIds == null || platformTemplateIds.isEmpty()) {
      missing.add("platformTemplateIds");
    }
    if (kol.getLandingUrl() == null || kol.getLandingUrl().isBlank()) {
      missing.add("landingUrl");
    }
    if (kol.getInviteCode() == null || kol.getInviteCode().isBlank()) {
      missing.add("inviteCode");
    }
    if (kol.getLinkTemplateId() == null) {
      missing.add("jumpTemplateId");
    }
    return List.copyOf(missing);
  }

  static Map<String, Object> snapshot(KolDO kol, List<Long> platformTemplateIds) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("promotionStatus", kol.getPromotionStatus());
    map.put("defaultRate", rate(kol.getDefaultRate()));
    map.put("platformTemplateIds", platformTemplateIds == null ? List.of() : platformTemplateIds);
    map.put("landingUrl", kol.getLandingUrl());
    map.put("inviteCode", kol.getInviteCode());
    map.put("sheetUrl", kol.getSheetUrl());
    map.put("jumpTemplateId", kol.getLinkTemplateId());
    return map;
  }

  private static String rate(BigDecimal rate) {
    return rate == null ? null : KolRules.rateText(rate);
  }
}
