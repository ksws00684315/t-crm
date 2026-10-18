package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** KOL as shown in lists and details; includes promotion fields (task 25a / ADR-023). */
public record KolDTO(
    Long id,
    String kolCode,
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    Long bdUserId,
    String bdUserName,
    BigDecimal defaultRate,
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
    Instant updateTime) {}
