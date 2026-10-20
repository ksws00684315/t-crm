package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.NotNull;

public record UpdateSystemConfigRequest(@NotNull String value) {}
