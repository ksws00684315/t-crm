package com.tcrm.admin.order.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CleanupKolRequest(@NotBlank @Size(max = 200) String reason) {}
