package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.Edition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * New company. The edition (ADR-038) is required and cannot be changed afterwards, so choosing it
 * is never implicit; a client that does not send one gets 400.
 */
public record CreateTenantRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9-]{2,32}") String tenantCode,
    @NotBlank @Size(max = 64) String name,
    @Size(max = 500) String remark,
    @NotNull Edition edition) {}
