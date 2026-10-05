package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.OperLogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN audit log list; validation 400, authorization AUTH_UNAUTHORIZED/AUTH_FORBIDDEN. */
@RestController
@RequestMapping("/admin-api/system/oper-logs")
@SaCheckRole("SUPER_ADMIN")
public class OperLogController {
  private final OperLogService logs;

  public OperLogController(OperLogService logs) {
    this.logs = logs;
  }

  @GetMapping
  public R<PageResult<OperLogVO>> page(
      @RequestParam(defaultValue = "1") @Min(1) @Max(PageResult.MAX_PAGE) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return R.ok(logs.page(page, size).map(OperLogVO::from));
  }
}
