package com.tcrm.admin.commission.api;

import com.tcrm.admin.commission.service.WithdrawalService;
import com.tcrm.admin.commission.service.WithdrawalService.FinanceFilter;
import com.tcrm.admin.security.api.AccountDTO;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalWorkbenchApi {
  private final WithdrawalService withdrawals;

  public WithdrawalWorkbenchApi(WithdrawalService withdrawals) {
    this.withdrawals = withdrawals;
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
