package com.tcrm.admin.kol.api;

/** Idle-KOL cleanup settings exposed to other modules (task 25a review). */
public record KolCleanupConfigDTO(boolean enabled, int idleDays) {}
