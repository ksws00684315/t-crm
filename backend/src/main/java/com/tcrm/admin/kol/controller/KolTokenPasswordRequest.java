package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bodies of the two token+password flows: invitation activation and password reset. Password rules
 * match the administrator side (task 12a §4.4): 10 to 72 printable ASCII characters.
 */
record KolTokenPasswordRequest(
    @NotBlank @Size(max = 64) String token,
    @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password) {}
