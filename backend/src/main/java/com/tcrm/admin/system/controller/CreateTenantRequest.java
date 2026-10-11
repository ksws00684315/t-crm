package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9-]{2,32}") String tenantCode,
    @NotBlank @Size(max = 64) String name,
    @Size(max = 500) String remark) {}
