package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record KolPasswordChangeRequest(
    @NotBlank @Size(max = 72) String oldPassword,
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String newPassword) {}
