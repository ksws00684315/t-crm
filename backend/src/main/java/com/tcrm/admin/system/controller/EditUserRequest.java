package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EditUserRequest(
    @NotBlank @Size(max = 64) String nickname,
    @Email @Size(max = 254) String email,
    @NotNull Role role,
    @NotNull DataScope dataScope) {}
