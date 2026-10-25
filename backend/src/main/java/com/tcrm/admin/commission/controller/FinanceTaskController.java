package com.tcrm.admin.commission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceFilter;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceTask;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified finance task list (13.03): commission withdrawals and creator prepays on one page,
 * filterable by number, creator, type and the shared lifecycle status. Action entries stay with the
 * type's own endpoints (review, payment).
 */
@RestController
public class FinanceTaskController {
  private final WithdrawalService withdrawals;
  private final CurrentUserContext current;

  public FinanceTaskController(WithdrawalService withdrawals, CurrentUserContext current) {
    this.withdrawals = withdrawals;
    this.current = current;
  }

  @GetMapping("/admin-api/commission/finance-tasks")
  @SaCheckPermission(
      value = {
        "withdrawal:review",
        "prepay:review",
        "prepay:finance-review",
        "prepay:pay",
        "commission:view"
      },
      mode = SaMode.OR)
  public R<PageResult<FinanceTaskVO>> list(@Valid @ModelAttribute Query request) {
    return R.ok(
        withdrawals
            .financeTasks(
                current.user(),
                new FinanceFilter(
                    request.applicationNo(),
                    request.kolId(),
                    request.type(),
                    request.status(),
                    request.pageNumber(),
                    request.pageSize()))
            .map(FinanceTaskVO::from));
  }

  public record Query(
      String applicationNo,
      @jakarta.validation.constraints.Min(1) Long kolId,
      String type,
      String status,
      @Min(1) Integer page,
      @Min(1) @Max(100) Integer size) {
    public int pageNumber() {
      return page == null ? 1 : page;
    }

    public int pageSize() {
      return size == null ? 20 : size;
    }
  }

  record FinanceTaskVO(
      String id,
      String taskNo,
      String kolId,
      String kolCode,
      String kolName,
      String bdId,
      String bdName,
      String taskType,
      String status,
      String amountCny,
      String currency,
      String createdAt) {
    static FinanceTaskVO from(FinanceTask row) {
      return new FinanceTaskVO(
          Long.toString(row.id()),
          row.taskNo(),
          Long.toString(row.kolId()),
          row.kolCode(),
          row.kolName(),
          row.bdId() == null ? null : row.bdId().toString(),
          row.bdName(),
          row.taskType(),
          row.status(),
          row.amountCny(),
          row.currency(),
          row.createdAt().toString());
    }
  }
}
