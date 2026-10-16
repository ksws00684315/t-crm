package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** KOL as shown in lists and details; the 2026-10-16 profile fields (task 11 §4.3) included. */
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
    Integer registerSource,
    Integer accountStatus,
    Instant createTime,
    Instant updateTime) {}
