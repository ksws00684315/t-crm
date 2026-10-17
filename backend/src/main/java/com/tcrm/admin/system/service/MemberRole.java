package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;

/**
 * Role, data scope, template and default codes of a membership (ADR-018). Super administrators
 * ignore a supplied template. A staff member requires a template. An omitted data scope uses the
 * template default, or ALL for a super administrator. OWN is rejected for anyone who is not staff.
 */
record MemberRole(Role role, DataScope dataScope, PostTemplate postTemplate, List<String> codes) {
  static MemberRole resolve(Role role, PostTemplate template, DataScope scope) {
    if (role == null || role == Role.PLATFORM_ADMIN) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (role == Role.SUPER_ADMIN) {
      if (scope == DataScope.OWN) {
        throw new BusinessException(ErrorCode.USER_INVALID_DATA_SCOPE);
      }
      return new MemberRole(
          Role.SUPER_ADMIN, scope == null ? DataScope.ALL : scope, null, List.of());
    }
    if (template == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return new MemberRole(
        Role.STAFF,
        scope == null ? template.dataScope() : scope,
        template,
        template.permissionCodes());
  }

  MemberRole withCodes(List<String> granted) {
    return new MemberRole(role, dataScope, postTemplate, granted);
  }
}
