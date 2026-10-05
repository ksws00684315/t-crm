package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.util.List;

/** New KOL; {@code bdUserId} is ignored for OWN users and may be null (unassigned). */
public record CreateKolCommand(
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    Long bdUserId,
    BigDecimal defaultRate,
    String remark) {}
