package com.tcrm.admin.stats.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.stats.service.WorkbenchService;
import com.tcrm.admin.stats.service.WorkbenchService.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkbenchController {
  private final WorkbenchService workbench;

  public WorkbenchController(WorkbenchService workbench) {
    this.workbench = workbench;
  }

  @GetMapping("/admin-api/workbench/overview")
  @SaCheckRole(value = {"SUPER_ADMIN", "STAFF"}, mode = SaMode.OR)
  public R<Overview> overview() {
    return R.ok(workbench.overview());
  }
}
