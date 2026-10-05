package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.BdUserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Selectable BDs (enabled OPERATOR + OWN) for pickers. SUPER_ADMIN and OPERATOR + ALL; an OPERATOR
 * with OWN scope gets AUTH_FORBIDDEN from the service.
 */
@RestController
@RequestMapping("/admin-api/system/bd-users")
@SaCheckRole(
    value = {"SUPER_ADMIN", "OPERATOR"},
    mode = SaMode.OR)
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
