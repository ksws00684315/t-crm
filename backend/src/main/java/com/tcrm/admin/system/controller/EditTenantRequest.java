package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditTenantRequest(
    @NotBlank @Size(max = 64) String name, @Size(max = 500) String remark) {}
