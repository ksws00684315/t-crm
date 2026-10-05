package com.tcrm.admin.kol.api;

/**
 * KOL reference for other modules: identity, display name, whether the KOL is enabled, and the
 * current BD ({@code null} when unassigned) so callers can apply the BD data scope.
 */
public record KolRefDTO(Long id, String kolCode, String name, boolean enabled, Long bdUserId) {}
