package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolDTO;
import com.tcrm.admin.kol.service.KolRules;
import java.time.Instant;
import java.util.List;

/**
 * {@code defaultRate} is a fraction with 4 decimals ("0.1550"), null when not set. The 2026-10-16
 * profile fields (task 11 §4.3): {@code whatsapp}, {@code linkTemplateId} with {@code
 * linkTemplateName}, {@code inviteCode}, {@code sheetUrl}, {@code registerSource} (1 entered in
 * admin, 2 self-registered), and {@code accountStatus} (0 unactivated, 1 activated; task 12c).
 */
public record KolVO(
    Long id,
    String kolCode,
    String name,
    String email,
    String country,
    List<SocialAccountVO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    String defaultRate,
    Integer status,
    String remark,
    String whatsapp,
    Long linkTemplateId,
    String linkTemplateName,
    String inviteCode,
    String sheetUrl,
    Integer registerSource,
    Integer accountStatus,
    Instant createTime,
    Instant updateTime) {
  static KolVO from(KolDTO k) {
    return new KolVO(
        k.id(),
        k.kolCode(),
        k.name(),
        k.email(),
        k.country(),
        k.socialAccounts().stream().map(SocialAccountVO::from).toList(),
        k.bdUserId(),
        k.bdUserName(),
        KolRules.rateText(k.defaultRate()),
        k.status(),
        k.remark(),
        k.whatsapp(),
        k.linkTemplateId(),
        k.linkTemplateName(),
        k.inviteCode(),
        k.sheetUrl(),
        k.registerSource(),
        k.accountStatus(),
        k.createTime(),
        k.updateTime());
  }
}
