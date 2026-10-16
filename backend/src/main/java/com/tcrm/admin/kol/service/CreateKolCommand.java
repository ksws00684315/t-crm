package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * New KOL; {@code bdUserId} is ignored for OWN users and may be null (unassigned). The optional
 * profile fields (task 11 §4.3) are null when unset; {@code linkTemplateId} was validated to be an
 * enabled template of the company.
 */
public record CreateKolCommand(
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    Long bdUserId,
    BigDecimal defaultRate,
    String remark,
    String whatsapp,
    Long linkTemplateId,
    String inviteCode,
    String sheetUrl) {}
