package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Company name and remark. The edition (ADR-038) is deliberately absent: a company keeps the
 * edition it was created with, and a request that carries one has it ignored rather than rejected,
 * so an unchanged form keeps saving.
 */
public record EditTenantRequest(
    @NotBlank @Size(max = 64) String name, @Size(max = 500) String remark) {}
