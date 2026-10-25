package com.tcrm.admin.commission.job;

import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily wallet reconciliation (design §4.8: 余额每天与明细合计核对一次，不一致记 ERROR). Task 63 adds the withdrawal
 * comparison: the funding occupation (pending + paid applications) must sit inside the wallet
 * balance, and the balance must equal the ledger sum.
 */
@Component
public class CommissionWalletReconcileJob {
  static final String LOCK = "commission:wallet-reconcile";
  private static final Logger LOG = LoggerFactory.getLogger(CommissionWalletReconcileJob.class);

  private final DistributedLocks locks;
  private final EnabledTenantApi tenants;
  private final CommissionWalletMapper wallets;
  private final WithdrawalMapper withdrawals;

  public CommissionWalletReconcileJob(
      DistributedLocks locks,
      EnabledTenantApi tenants,
      CommissionWalletMapper wallets,
      WithdrawalMapper withdrawals) {
    this.locks = locks;
    this.tenants = tenants;
    this.wallets = wallets;
    this.withdrawals = withdrawals;
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
  public void reconcile() throws InterruptedException {
    locks.run(
        LOCK,
        0,
        -1,
        () -> {
          for (Long tenant : tenants.enabledTenantIds()) {
            long tenantId = tenant;
            TenantScope.runCompany(tenantId, this::reconcileCompany);
          }
        });
  }

  private void reconcileCompany() {
    int mismatches = 0;
    for (Long kolId : wallets.listWalletKolIds()) {
      BigDecimal balance = wallets.findBalance(kolId);
      BigDecimal ledger = wallets.sumLedgerCny(kolId);
      if (balance == null || balance.compareTo(ledger == null ? BigDecimal.ZERO : ledger) != 0) {
        LOG.error(
            "wallet balance mismatch: tenant {}, kol {}, balance {}, ledger sum {}",
            TenantScope.companyId(),
            kolId,
            balance,
            ledger);
        mismatches++;
        continue;
      }
      BigDecimal funding = withdrawals.sumFundingOccupied(kolId);
      // Funding money must stay inside the balance; a negative balance with pending withdrawals
      // would mean a prepay deduction raced past an approval and needs manual attention.
      if (balance.subtract(funding).signum() < 0) {
        LOG.error(
            "withdrawal occupation exceeds wallet balance: tenant {}, kol {}, balance {},"
                + " funding occupation {}",
            TenantScope.companyId(),
            kolId,
            balance,
            funding);
        mismatches++;
      }
    }
    if (mismatches > 0) {
      LOG.error("wallet reconciliation found {} mismatched creators", mismatches);
    }
  }
}
