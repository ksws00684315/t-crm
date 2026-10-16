package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record KolLoginRequest(
    @NotBlank @Email @Size(max = 128) String email, @NotBlank @Size(max = 72) String password) {}
