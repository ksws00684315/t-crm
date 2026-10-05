package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password) {}
