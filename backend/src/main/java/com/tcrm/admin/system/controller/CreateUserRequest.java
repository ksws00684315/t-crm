package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password,
    @NotBlank @Size(max = 64) String nickname,
    @Email @Size(max = 254) String email,
    @NotNull Role role,
    @NotNull DataScope dataScope) {}
