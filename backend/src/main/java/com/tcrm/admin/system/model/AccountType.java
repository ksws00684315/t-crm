package com.tcrm.admin.system.model;

/**
 * Kind of {@code sys_user} row. A member's role in each company is stored on its membership ({@link
 * SysUserTenantDO}), not here.
 */
public enum AccountType {
  PLATFORM_ADMIN,
  MEMBER
}
