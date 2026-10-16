package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import java.util.List;

/**
 * Audited permission replacement. Both sets are logged: {@code previousPermissions} is what the
 * staff account held, {@code permissions} is what it holds after the call.
 */
public record PermissionChange(
    Long userId,
    Long tenantId,
    List<String> previousPermissions,
    List<String> permissions,
    DataScope previousDataScope,
    DataScope dataScope) {}
