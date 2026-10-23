package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.WithdrawalDetailDTO;
import com.tcrm.admin.commission.service.WithdrawalDetailService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only detail API. Application creation and payment are outside this delivery. */
@RestController
@SaCheckPermission(
    value = {"commission:view", "withdrawal:review"},
    mode = SaMode.OR)
public class WithdrawalDetailController {
  private final WithdrawalDetailService service;
  private final CurrentUserContext current;

  public WithdrawalDetailController(WithdrawalDetailService service, CurrentUserContext current) {
    this.service = service;
    this.current = current;
  }

  @GetMapping("/admin-api/commission/withdrawals/{id}")
  @AuditLog(module = "Withdrawal", action = "View detail")
  public R<WithdrawalDetailDTO> detail(@PathVariable @Min(1) long id) {
    return R.ok(service.detail(current.user(), id));
  }

  @GetMapping("/admin-api/commission/withdrawals/{id}/orders")
  public R<PageResult<WithdrawalDetailDTO.Order>> orders(
      @PathVariable @Min(1) long id,
      @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
    return R.ok(service.orders(current.user(), id, page, size));
  }
}
