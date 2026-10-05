package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 1 enabled, 0 disabled. */
public record KolStatusRequest(@NotNull @Min(0) @Max(1) Integer status) {}
