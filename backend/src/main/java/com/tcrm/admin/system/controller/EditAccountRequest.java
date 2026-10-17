package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditAccountRequest(
    @NotBlank @Size(max = 64) String nickname, @Email @Size(max = 254) String email) {}
