package com.tcrm.admin.kol.service;

import java.util.List;

/**
 * Profile fields editable via ordinary KOL edit (task 28 §3.2 / §3.3). Commission rate, jump
 * template, invite code and sheet URL are edited only through promotion config; email is read-only.
 */
public record EditKolCommand(
    String name,
    String country,
    List<SocialAccountDTO> socialAccounts,
    String remark,
    String whatsapp) {}
