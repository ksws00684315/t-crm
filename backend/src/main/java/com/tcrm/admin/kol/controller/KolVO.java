package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.KolDTO;
import com.tcrm.admin.kol.service.KolRules;
import java.time.Instant;
import java.util.List;

/**
 * List/detail row (task 29 / ADR-025). {@code defaultRate} is a fraction with 4 decimals
 * ("0.1550"), null when not set. IDs in nested platforms are strings.
 */
public record KolVO(
    Long id,
    String kolCode,
    String name,
    String email,
    String phone,
    String country,
    List<SocialAccountVO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    String defaultRate,
    Integer status,
    String remark,
    String whatsapp,
    Long jumpTemplateId,
    String jumpTemplateName,
    String inviteCode,
    String sheetUrl,
    String reviewStatus,
    String relationStatus,
    String displayRelationStatus,
    String loginStatus,
    List<PlatformVO> platforms,
    Integer configVersion,
    Instant inviteTime,
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
        k.phone(),
        k.country(),
        k.socialAccounts().stream().map(SocialAccountVO::from).toList(),
        k.bdUserId(),
        k.bdUserName(),
        KolRules.rateText(k.defaultRate()),
        k.status(),
        k.remark(),
        k.whatsapp(),
        k.jumpTemplateId(),
        k.jumpTemplateName(),
        k.inviteCode(),
        k.sheetUrl(),
        k.reviewStatus(),
        k.relationStatus(),
        k.displayRelationStatus(),
        k.loginStatus(),
        k.platforms().stream()
            .map(
                p ->
                    new PlatformVO(
                        p.templateId() == null ? null : Long.toString(p.templateId()),
                        p.templateName(),
                        p.address()))
            .toList(),
        k.configVersion(),
        k.inviteTime(),
        k.registerSource(),
        k.accountStatus(),
        k.createTime(),
        k.updateTime());
  }

  public record PlatformVO(String templateId, String templateName, String address) {}
}
