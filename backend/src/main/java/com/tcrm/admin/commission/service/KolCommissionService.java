package com.tcrm.admin.commission.service;

import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.KolMoneyRow;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator-facing commission overview and fund records (task 62, requirement 11.02, design §6.3).
 * The KOL identity comes from the login; occupied stays zero until task 63 stores withdrawal
 * applications.
 */
@Service
public class KolCommissionService {
  private final CommissionItemStateMapper states;
  private final CommissionFinanceQueryMapper finance;
  private final WithdrawalMapper withdrawals;
  private final Clock clock;

  @Autowired
  public KolCommissionService(
      CommissionItemStateMapper states,
      CommissionFinanceQueryMapper finance,
      WithdrawalMapper withdrawals) {
    this(states, finance, withdrawals, Clock.systemUTC());
  }

  KolCommissionService(
      CommissionItemStateMapper states,
      CommissionFinanceQueryMapper finance,
      WithdrawalMapper withdrawals,
      Clock clock) {
    this.states = states;
    this.finance = finance;
    this.withdrawals = withdrawals;
    this.clock = clock;
  }

  /** Five-layer money of the overview (amounts scale 2, all plain strings in the VO). */
  public record OverviewMoney(
      BigDecimal estimated,
      BigDecimal frozen,
      BigDecimal available,
      BigDecimal occupied,
      BigDecimal cumulativePaid,
      BigDecimal deducted,
      BigDecimal pendingDeduction,
      BigDecimal prepayPaid,
      BigDecimal paid,
      BigDecimal commissionPaid) {}

  /** Overview payload: {@code income} plus the calculation time. */
  public record Overview(Income income, Instant asOf) {}

  /** Same shape as the admin income metrics (ADR-032 appendix B). */
  public record Income(
      String estimated,
      String frozen,
      String available,
      String occupied,
      String cumulativePaid,
      String deducted,
      String pendingDeduction,
      String prepayPaid,
      String paid,
      String commissionPaid) {}

  /** One unified payment record (prepay now; commission withdrawals join in task 63). */
  public record FundRecord(
      long id,
      String type,
      Instant appliedAt,
      String applicationNo,
      String amount,
      String currency,
      String maskedRecipient,
      Instant receivedAt,
      String publicStatus,
      long sourceId) {}

  @Transactional(readOnly = true)
  public Overview overview(long tenantId, long kolId) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          List<Long> ids = List.of(kolId);
          BigDecimal estimated = BigDecimal.ZERO.setScale(2);
          BigDecimal frozen = BigDecimal.ZERO.setScale(2);
          for (var row : states.aggregateByKol(ids)) {
            estimated = row.estimatedCny();
            frozen = row.frozenCny();
          }
          Map<Long, BigDecimal> balances = moneyMap(finance.walletBalances(ids));
          Map<Long, BigDecimal> deducted = moneyMap(finance.recoverySums(ids));
          Map<Long, BigDecimal> pending = moneyMap(finance.pendingDeductions(ids));
          Map<Long, BigDecimal> commissionPaid = moneyMap(finance.withdrawalPaidSums(ids));
          Map<Long, BigDecimal> prepayPaid = prepayMap(finance.prepayPaidSums(ids, null, null));
          // In-flight withdrawals hold the occupied money (task 63)
          BigDecimal occupied = nz(withdrawals.sumActiveOccupied(kolId));
          BigDecimal balance = balances.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
          BigDecimal commission = commissionPaid.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
          BigDecimal prepay = prepayPaid.getOrDefault(kolId, BigDecimal.ZERO.setScale(2));
          OverviewMoney money =
              new OverviewMoney(
                  estimated,
                  frozen,
                  balance.subtract(occupied),
                  occupied,
                  commission.add(prepay),
                  deducted.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)),
                  pending.getOrDefault(kolId, BigDecimal.ZERO.setScale(2)),
                  prepay,
                  commission.add(prepay),
                  commission);
          return new Overview(
              new Income(
                  text(money.estimated()),
                  text(money.frozen()),
                  text(money.available()),
                  text(money.occupied()),
                  text(money.cumulativePaid()),
                  text(money.deducted()),
                  text(money.pendingDeduction()),
                  text(money.prepayPaid()),
                  text(money.paid()),
                  text(money.commissionPaid())),
              clock.instant());
        });
  }

  @Transactional(readOnly = true)
  public PageResult<FundRecord> fundRecords(long tenantId, long kolId, int page, int size) {
    return TenantScope.callCompany(
        tenantId,
        () -> {
          // Commission withdrawals (task 63) and prepay payments on one page, newest first.
          List<FundRecord> all = new ArrayList<>();
          for (var row : withdrawals.listKolPaidWithdrawals(kolId)) {
            all.add(toWithdrawalRecord(row));
          }
          for (PrepayPaymentRow row : finance.prepayPayments(List.of(kolId), null, null)) {
            all.add(toRecord(row));
          }
          all.sort(
              Comparator.comparing(
                      FundRecord::appliedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                  .thenComparing(FundRecord::sourceId, Comparator.reverseOrder()));
          long total = all.size();
          int from = (page - 1) * size;
          List<FundRecord> items =
              from >= all.size() ? List.of() : all.subList(from, Math.min(from + size, all.size()));
          return new PageResult<>(items, total, page, size);
        });
  }

  /** One paid withdrawal as the creator sees it (masked recipient, public status). */
  private static FundRecord toWithdrawalRecord(WithdrawalMapper.KolPaidWithdrawalRow row) {
    return new FundRecord(
        row.withdrawalId(),
        "COMMISSION",
        row.submittedAt(),
        row.applicationNo(),
        row.amountCny().toPlainString(),
        "CNY",
        row.accountMasked(),
        row.paidAt(),
        "PAID",
        row.withdrawalId());
  }

  private static FundRecord toRecord(PrepayPaymentRow row) {
    return new FundRecord(
        row.paymentId(),
        "PREPAY",
        row.appliedAt(),
        row.sourceNo(),
        row.amountCny().toPlainString(),
        "CNY",
        row.maskedRecipient(),
        row.paidAt(),
        publicStatus(row.sourceStatus()),
        row.sourceId());
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2) : value;
  }

  /** Creators see paid results only; the status maps to the public wording of §6.3. */
  private static String publicStatus(String prepayStatus) {
    return "PAID".equals(prepayStatus) ? "PAID" : prepayStatus == null ? "" : prepayStatus;
  }

  private static String text(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
  }

  private static Map<Long, BigDecimal> moneyMap(List<KolMoneyRow> rows) {
    Map<Long, BigDecimal> map = new HashMap<>();
    for (KolMoneyRow row : rows) {
      map.put(row.kolId(), row.amountCny() == null ? BigDecimal.ZERO.setScale(2) : row.amountCny());
    }
    return map;
  }

  private static Map<Long, BigDecimal> prepayMap(
      List<CommissionFinanceQueryMapper.PrepayPaidRow> rows) {
    Map<Long, BigDecimal> map = new HashMap<>();
    for (var row : rows) {
      map.put(row.kolId(), row.amountCny() == null ? BigDecimal.ZERO.setScale(2) : row.amountCny());
    }
    return map;
  }
}
