package com.tcrm.admin.kol.api;

/** KOL reference for other modules: identity, display name and whether the KOL is enabled. */
public record KolRefDTO(Long id, String kolCode, String name, boolean enabled) {}
