package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolDTO;
import com.tcrm.admin.kol.service.KolRules;
import java.time.Instant;
import java.util.List;

/**
 * List/detail row. {@code defaultRate} is a fraction with 4 decimals ("0.1550"), null when not set.
 * Promotion fields (task 25a / ADR-023): {@code promotionStatus}, {@code displayStatus} (may be
 * {@code PENDING_UPDATE}), {@code landingUrl}, {@code platformTemplateIds}.
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
    String promotionStatus,
    String displayStatus,
    String landingUrl,
    List<Long> platformTemplateIds,
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
        k.promotionStatus(),
        k.displayStatus(),
        k.landingUrl(),
        k.platformTemplateIds(),
        k.registerSource(),
        k.accountStatus(),
        k.createTime(),
        k.updateTime());
  }
}
