package com.tcrm.admin.commission.api;

import com.tcrm.admin.commission.service.PrepayService;
import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceFilter;
import com.tcrm.admin.security.api.AccountDTO;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalWorkbenchApi {
  private final WithdrawalService withdrawals;

  private final PrepayService prepays;

  public WithdrawalWorkbenchApi(WithdrawalService withdrawals, PrepayService prepays) {
    this.withdrawals = withdrawals;
    this.prepays = prepays;
  }

  public long pendingPrepays(AccountDTO user, List<String> permissions) {
    long total = 0;
    if (permissions.contains("prepay:review")) {
      total +=
          prepays
              .page(user, new PrepayService.Filter(null, null, "PENDING_OPERATIONS_REVIEW", 1, 1))
              .total();
    }
    if (permissions.contains("prepay:finance-review")) {
      total +=
          prepays
              .page(user, new PrepayService.Filter(null, null, "PENDING_FINANCE_REVIEW", 1, 1))
              .total();
    }
    if (permissions.contains("prepay:pay")) {
      total += prepays.page(user, new PrepayService.Filter(null, null, "APPROVED", 1, 1)).total();
    }
    return total;
  }

  public Pending pending(AccountDTO user) {
    var page =
        withdrawals.financeTasks(
            user, new FinanceFilter(null, null, "COMMISSION", "ACTION_REQUIRED", 1, 5));
    return new Pending(
        page.total(),
        page.items().stream()
            .map(
                row ->
                    new Application(row.applicationNo(), row.amount(), row.status(), row.paused()))
            .toList());
  }

  public record Pending(long total, List<Application> rows) {}

  public record Application(String applicationNo, String amount, String status, boolean paused) {}
}
