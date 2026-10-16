package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Replaces a staff account's permission codes and data scope. An empty list revokes every code. */
public record ReplacePermissionsRequest(
    @NotNull List<String> permissions, @NotNull DataScope dataScope) {}
