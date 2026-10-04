package com.tcrm.admin.system;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.common.*;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin-api/system/oper-log")
@SaCheckRole("SUPER_ADMIN")
public class OperLogController {
  private final OperLogMapper logs;

  public OperLogController(OperLogMapper logs) {
    this.logs = logs;
  }

  @GetMapping
  public R<PageResult<OperLog>> list(
      @RequestParam(defaultValue = "1") @Min(1) long page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
    var result =
        logs.selectPage(
            new Page<>(page, size), new QueryWrapper<OperLog>().orderByDesc("oper_time", "id"));
    return R.ok(new PageResult<>(result.getRecords(), result.getTotal()));
  }
}
