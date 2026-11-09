package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.security.api.Edition;
import com.tcrm.admin.security.api.EditionScope;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.UserPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Permission codes and post templates a super administrator can assign, limited to the codes of the
 * company's edition (ADR-038 §2.4); open to both editions.
 */
@RestController
@RequestMapping("/admin-api/system/permissions")
@EditionScope({Edition.DISTRIBUTION, Edition.KOL_LIBRARY})
@SaCheckRole("SUPER_ADMIN")
public class PermissionController {
  private final UserPermissionService permissions;

  public PermissionController(UserPermissionService permissions) {
    this.permissions = permissions;
  }

  @GetMapping
  public R<PermissionCatalogVO> catalog() {
    return R.ok(PermissionCatalogVO.from(permissions.catalog()));
  }
}
