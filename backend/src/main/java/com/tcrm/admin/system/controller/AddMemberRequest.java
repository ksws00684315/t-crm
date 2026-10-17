package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddMemberRequest(
    @NotNull @Min(1) Long userId,
    @NotNull @CompanyRole Role role,
    PostTemplate postTemplate,
    DataScope dataScope) {}
