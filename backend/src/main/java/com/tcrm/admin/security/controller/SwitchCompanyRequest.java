package com.tcrm.admin.security.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SwitchCompanyRequest(@NotNull @Min(1) Long tenantId) {}
