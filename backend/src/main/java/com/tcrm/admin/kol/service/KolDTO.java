package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** KOL as shown in lists and details (task 29 / ADR-025). */
public record KolDTO(
    Long id,
    String kolCode,
    String name,
    String email,
    String phone,
    String country,
    List<SocialAccountDTO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    BigDecimal defaultRate,
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
    List<PlatformDTO> platforms,
    Integer configVersion,
    Integer autoCleanupDays,
    Instant inviteTime,
    Integer registerSource,
    Integer accountStatus,
    Instant createTime,
    Instant updateTime) {
  public record PlatformDTO(Long templateId, String templateName, String address) {}
}
