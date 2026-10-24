package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creator withdrawal freeze (commission:freeze, §4.8/11.03): frozen creators cannot submit new
 * applications; in-flight ones are untouched; resume lifts the block. History stays in the freeze
 * table for auditing.
 */
@RestController
public class WithdrawalFreezeController {
  private final WithdrawalService withdrawals;
  private final CurrentUserContext current;

  public WithdrawalFreezeController(WithdrawalService withdrawals, CurrentUserContext current) {
    this.withdrawals = withdrawals;
    this.current = current;
  }

  /** Freezes a creator's new withdrawal applications; the reason is required. */
  @PostMapping("/admin-api/commission/withdrawal-freezes")
  @SaCheckPermission("commission:freeze")
  public R<Void> freeze(@Valid @RequestBody FreezeRequest request) {
    withdrawals.freeze(current.user(), request.kolId(), request.reason());
    return R.ok(null);
  }

  /** Lifts the freeze so the creator can apply again. */
  @PutMapping("/admin-api/commission/withdrawal-freezes/{kolId}/resume")
  @SaCheckPermission("commission:freeze")
  public R<Void> resume(
      @PathVariable @Min(1) long kolId, @Valid @RequestBody ResumeRequest request) {
    withdrawals.resume(current.user(), kolId, request.reason());
    return R.ok(null);
  }

  public record FreezeRequest(
      @NotNull @Min(1) Long kolId, @NotBlank @Size(max = 500) String reason) {}

  public record ResumeRequest(@Size(max = 500) String reason) {}
}
