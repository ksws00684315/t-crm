package com.tcrm.admin.kol.service;

import java.math.BigDecimal;
import java.util.List;

/** Editable KOL fields; the code and the BD are changed elsewhere (never here). */
public record EditKolCommand(
    String name,
    String email,
    String country,
    List<SocialAccountDTO> socialAccounts,
    BigDecimal defaultRate,
    String remark) {}
