package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.BdUserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Selectable BDs: enabled staff accounts with OWN scope and kol:edit. Callers need kol:view,
 * including a staff account whose own scope is OWN.
 */
@RestController
@RequestMapping("/admin-api/system/bd-users")
@SaCheckPermission("kol:view")
public class BdUserController {
  private final BdUserService bdUsers;

  public BdUserController(BdUserService bdUsers) {
    this.bdUsers = bdUsers;
  }

  @GetMapping
  public R<List<BdUserVO>> list() {
    return R.ok(bdUsers.listSelectable().stream().map(BdUserVO::from).toList());
  }
}
