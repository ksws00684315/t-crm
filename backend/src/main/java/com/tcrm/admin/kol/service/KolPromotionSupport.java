package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.RelationStatus;
import com.tcrm.admin.kol.model.ReviewStatus;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared promotion rules (ADR-025): display relation status, config completeness, snapshots. */
final class KolPromotionSupport {
  private KolPromotionSupport() {}

  /**
   * Display relation status: a frozen KOL is invalid; otherwise an unusable BD is pending update;
   * otherwise show the stored promotion state.
   */
  static String displayRelationStatus(Integer loginStatus, String relationStatus, BdUserDTO bd) {
    if (Integer.valueOf(0).equals(loginStatus)) {
      return RelationStatus.INVALID.name();
    }
    if (bd == null || !bd.usable()) {
      return RelationStatus.DISPLAY_PENDING_UPDATE;
    }
    return relationStatus;
  }

  static boolean configComplete(KolDO kol, List<PlatformRef> platforms) {
    return missingConfigFields(kol, platforms).isEmpty();
  }

  /** Missing field names for a complete promotion configuration. A jump template is optional. */
  static List<String> missingConfigFields(KolDO kol, List<PlatformRef> platforms) {
    List<String> missing = new ArrayList<>(7);
    if (!ReviewStatus.APPROVED.name().equals(kol.getReviewStatus())) {
      missing.add("review");
    }
    if (kol.getDefaultRate() == null) {
      missing.add("defaultRate");
    }
    if (kol.getInviteCode() == null || kol.getInviteCode().isBlank()) {
      missing.add("inviteCode");
    }
    if (platforms == null || platforms.isEmpty()) {
      missing.add("platforms");
    } else {
      boolean anyBlank =
          platforms.stream().anyMatch(p -> p.address() == null || p.address().isBlank());
      if (anyBlank) {
        missing.add("platformAddress");
      }
    }
    if (kol.getSheetUrl() == null || kol.getSheetUrl().isBlank()) {
      missing.add("sheetUrl");
    }
    if (kol.getPromotionRemark() == null || kol.getPromotionRemark().isBlank()) {
      missing.add("remark");
    }
    return List.copyOf(missing);
  }

  static Map<String, Object> snapshot(KolDO kol, List<PlatformRef> platforms) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("reviewStatus", kol.getReviewStatus());
    map.put("relationStatus", kol.getRelationStatus());
    map.put("defaultRate", rate(kol.getDefaultRate()));
    map.put(
        "platforms",
        platforms == null
            ? List.of()
            : platforms.stream()
                .map(
                    p -> {
                      Map<String, Object> row = new LinkedHashMap<>();
                      row.put("templateId", p.templateId());
                      row.put("address", p.address());
                      return row;
                    })
                .toList());
    map.put("inviteCode", kol.getInviteCode());
    map.put("sheetUrl", kol.getSheetUrl());
    map.put("remark", kol.getPromotionRemark());
    map.put("jumpTemplateId", kol.getLinkTemplateId());
    map.put("configVersion", kol.getConfigVersion());
    map.put("phone", kol.getPhone());
    map.put("name", kol.getName());
    map.put("whatsapp", kol.getWhatsapp());
    map.put("country", kol.getCountry());
    return map;
  }

  /** Profile-oriented snapshot for REVIEW records. */
  static Map<String, Object> profileSnapshot(KolDO kol) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", kol.getName());
    map.put("phone", kol.getPhone());
    map.put("whatsapp", kol.getWhatsapp());
    map.put("country", kol.getCountry());
    map.put("remark", kol.getRemark());
    map.put("socialAccounts", kol.getSocialAccounts());
    map.put("reviewStatus", kol.getReviewStatus());
    return map;
  }

  private static String rate(BigDecimal rate) {
    return rate == null ? null : KolRules.rateText(rate);
  }

  record PlatformRef(Long templateId, String address) {}
}
