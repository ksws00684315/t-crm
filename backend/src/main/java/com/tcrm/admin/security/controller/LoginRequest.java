package com.tcrm.admin.security.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
    @NotBlank @Size(max = 72) String password)
    implements com.tcrm.admin.security.api.LoginSubject {}
