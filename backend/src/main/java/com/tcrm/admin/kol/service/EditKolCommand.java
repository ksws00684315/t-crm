package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Editable KOL fields; the code and the BD are changed elsewhere (never here). The optional profile
 * fields (task 11 §4.3) are null to clear them; {@code linkTemplateId} was validated to be an
 * enabled template of the company.
 */
public record EditKolCommand(
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    BigDecimal defaultRate,
    String remark,
    String whatsapp,
    Long linkTemplateId,
    String inviteCode,
    String sheetUrl) {}
