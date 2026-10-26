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
import java.util.List;
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
  public R<FinanceTaskPageVO> list(@Valid @ModelAttribute Query request) {
    return R.ok(
        FinanceTaskPageVO.from(
            withdrawals.financeTasks(
                current.user(),
                new FinanceFilter(
                    request.applicationNo(),
                    request.kolId(),
                    request.type(),
                    request.status(),
                    request.pageNumber(),
                    request.pageSize()))));
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

  /**
   * Page of the unified finance task list (13.03). {@code total} is a JSON number (ADR-032); {@code
   * int} keeps it out of the shared long-to-string serializer.
   */
  record FinanceTaskPageVO(List<FinanceTaskVO> items, int total, int page, int size) {
    static FinanceTaskPageVO from(PageResult<FinanceTask> page) {
      return new FinanceTaskPageVO(
          page.items().stream().map(FinanceTaskVO::from).toList(),
          Math.toIntExact(page.total()),
          page.page(),
          page.size());
    }
  }

  /**
   * One row of the unified finance task list (contract §6.2; {@code bdId}/{@code bdName} extra).
   */
  record FinanceTaskVO(
      String id,
      String type,
      String applicationNo,
      String kolId,
      String kolCode,
      String kolName,
      String amount,
      String currency,
      String status,
      String createdAt,
      String bdId,
      String bdName) {
    static FinanceTaskVO from(FinanceTask row) {
      return new FinanceTaskVO(
          Long.toString(row.id()),
          row.type(),
          row.applicationNo(),
          Long.toString(row.kolId()),
          row.kolCode(),
          row.kolName(),
          row.amount(),
          row.currency(),
          row.status(),
          row.createdAt().toString(),
          row.bdId() == null ? null : row.bdId().toString(),
          row.bdName());
    }
  }
}
