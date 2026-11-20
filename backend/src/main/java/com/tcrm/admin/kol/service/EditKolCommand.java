package com.tcrm.admin.kol.service;

import java.util.List;

/**
 * Profile fields editable via ordinary KOL edit (task 28 / 29). Commission rate, jump template,
 * invite code and sheet URL are edited only through promotion config; email is read-only. {@code
 * bdDiscord} and {@code bdWhatsapp} are the company business accounts managing the creator (task
 * 156); the inviter name itself is derived from the current BD and is not writable here.
 */
public record EditKolCommand(
    String name,
    String phone,
    String country,
    List<SocialAccountDTO> socialAccounts,
    String remark,
    String whatsapp,
    String bdDiscord,
    String bdWhatsapp) {}
