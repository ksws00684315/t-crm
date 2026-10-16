package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record KolSelectCompanyRequest(
    @NotBlank @Size(max = 64) String selectionToken, @NotNull @Min(1) Long tenantId) {}
