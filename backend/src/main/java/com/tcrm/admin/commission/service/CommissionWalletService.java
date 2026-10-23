package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Production write service for commission wallet balances and immutable ledger entries. */
@Service
public class CommissionWalletService {
  private final JdbcTemplate jdbc;
  private final KolApi kols;
  private final SystemConfigApi configs;
  private final Clock clock;

  @Autowired
  public CommissionWalletService(JdbcTemplate jdbc, KolApi kols, SystemConfigApi configs) {
    this(jdbc, kols, configs, Clock.systemUTC());
  }

  CommissionWalletService(JdbcTemplate jdbc, KolApi kols, SystemConfigApi configs, Clock clock) {
    this.jdbc = jdbc;
    this.kols = kols;
    this.configs = configs;
    this.clock = clock;
  }

  public record Balance(
      String availableCny, String minimumWithdrawalCny, boolean withdrawalEligible) {}

  public record CreditCommand(
      long tenantId,
      long kolId,
      long sourceId,
      String sourceKey,
      BigDecimal amountCny,
      Instant occurredAt) {}

  /**
   * Posts an actual commission credit and assigns it to outstanding prepay deductions in stable
   * FIFO order. Commission settlement code must use this method instead of writing wallet rows.
   */
  @Transactional(rollbackFor = Exception.class)
  public BigDecimal postCommissionCredit(CreditCommand command) {
    validateCredit(command);
    return TenantScope.callCompany(
        command.tenantId(),
        () -> {
          if (kols.listByIds(List.of(command.kolId())).isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
          }
          return postCredit(command);
        });
  }

  @Transactional(rollbackFor = Exception.class)
  public BigDecimal postPrepayDeduction(
      long tenantId,
      long kolId,
      long prepayId,
      BigDecimal amount,
      Instant occurredAt,
      long operatorId) {
    if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        tenantId,
        () -> {
          ensureWallet(tenantId, kolId);
          BigDecimal balance = lockBalance(tenantId, kolId);
          String sourceKey = "PREPAY_DEDUCT:" + prepayId;
          BigDecimal existing =
              existingEntry(tenantId, kolId, "PREPAY", prepayId, sourceKey, amount.negate());
          if (existing != null) {
            return existing;
          }
          BigDecimal next = balance.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
          Instant time = truncate(occurredAt);
          jdbc.update(
              "INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny, source_type, source_id, source_key, occurred_at, balance_after_cny, create_by, create_time) VALUES (?, ?, ?, 'PREPAY_DEDUCT', ?, 'PREPAY', ?, ?, ?, ?, ?, ?)",
              IdWorker.getId(),
              tenantId,
              kolId,
              amount.negate(),
              prepayId,
              sourceKey,
              Timestamp.from(time),
              next,
              operatorId,
              Timestamp.from(now()));
          jdbc.update(
              "UPDATE crm_commission_wallet SET balance_cny = ?, update_time = ? WHERE tenant_id = ? AND kol_id = ?",
              next,
              Timestamp.from(now()),
              tenantId,
              kolId);
          return next;
        });
  }

  @Transactional(readOnly = true)
  public Balance balance(long tenantId, long kolId, BigDecimal minimumWithdrawalCny) {
    BigDecimal available = currentBalance(tenantId, kolId);
    BigDecimal minimum = minimumWithdrawalCny.setScale(2, RoundingMode.UNNECESSARY);
    return new Balance(
        money(available),
        money(minimum),
        available.signum() >= 0 && available.compareTo(minimum) >= 0);
  }

  /** Balance and configured withdrawal threshold for the future withdrawal application flow. */
  @Transactional(readOnly = true)
  public Balance withdrawalEligibility(long tenantId, long kolId) {
    BigDecimal minimum = BigDecimal.valueOf(configs.getInt("commission.minimum-withdrawal-cny"));
    return balance(tenantId, kolId, minimum);
  }

  @Transactional(readOnly = true)
  public BigDecimal currentBalance(long tenantId, long kolId) {
    List<BigDecimal> result =
        jdbc.query(
            "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ?",
            (rs, index) -> rs.getBigDecimal(1),
            tenantId,
            kolId);
    return result.isEmpty() ? BigDecimal.ZERO.setScale(2) : result.getFirst();
  }

  private BigDecimal postCredit(CreditCommand command) {
    ensureWallet(command.tenantId(), command.kolId());
    BigDecimal balance = lockBalance(command.tenantId(), command.kolId());
    BigDecimal amount = command.amountCny().setScale(2, RoundingMode.UNNECESSARY);
    BigDecimal existing =
        existingEntry(
            command.tenantId(),
            command.kolId(),
            "COMMISSION",
            command.sourceId(),
            command.sourceKey(),
            amount);
    if (existing != null) {
      return existing;
    }
    BigDecimal next = balance.add(amount).setScale(2, RoundingMode.UNNECESSARY);
    Instant now = now();
    long creditId = IdWorker.getId();
    jdbc.update(
        "INSERT INTO crm_commission_ledger (id, tenant_id, kol_id, entry_type, amount_cny, source_type, source_id, source_key, occurred_at, balance_after_cny, create_by, create_time) VALUES (?, ?, ?, 'COMMISSION_CREDIT', ?, 'COMMISSION', ?, ?, ?, ?, NULL, ?)",
        creditId,
        command.tenantId(),
        command.kolId(),
        amount,
        command.sourceId(),
        command.sourceKey(),
        Timestamp.from(truncate(command.occurredAt())),
        next,
        Timestamp.from(now));
    allocateFifo(command, creditId, amount);
    jdbc.update(
        "UPDATE crm_commission_wallet SET balance_cny = ?, update_time = ? WHERE tenant_id = ? AND kol_id = ?",
        next,
        Timestamp.from(now),
        command.tenantId(),
        command.kolId());
    return next;
  }

  private void allocateFifo(CreditCommand command, long creditId, BigDecimal credit) {
    List<Outstanding> outstanding =
        jdbc.query(
            "SELECT l.id, -l.amount_cny - COALESCE(SUM(r.amount_cny), 0) AS remaining FROM crm_commission_ledger l LEFT JOIN crm_commission_recovery r ON r.tenant_id = l.tenant_id AND r.deduction_ledger_id = l.id WHERE l.tenant_id = ? AND l.kol_id = ? AND l.entry_type = 'PREPAY_DEDUCT' GROUP BY l.id, l.amount_cny, l.occurred_at HAVING -l.amount_cny > COALESCE(SUM(r.amount_cny), 0) ORDER BY l.occurred_at, l.id",
            this::outstanding,
            command.tenantId(),
            command.kolId());
    BigDecimal remainingCredit = credit;
    for (Outstanding deduction : outstanding) {
      if (remainingCredit.signum() == 0) {
        break;
      }
      BigDecimal amount = remainingCredit.min(deduction.remaining());
      jdbc.update(
          "INSERT INTO crm_commission_recovery (id, tenant_id, kol_id, credit_ledger_id, deduction_ledger_id, amount_cny, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
          IdWorker.getId(),
          command.tenantId(),
          command.kolId(),
          creditId,
          deduction.ledgerId(),
          amount,
          Timestamp.from(now()));
      remainingCredit = remainingCredit.subtract(amount);
    }
  }

  private Outstanding outstanding(ResultSet rs, int index) throws SQLException {
    return new Outstanding(rs.getLong("id"), rs.getBigDecimal("remaining"));
  }

  private void ensureWallet(long tenantId, long kolId) {
    Instant now = now();
    jdbc.update(
        "INSERT INTO crm_commission_wallet (id, tenant_id, kol_id, balance_cny, create_time, update_time) VALUES (?, ?, ?, 0, ?, ?) ON CONFLICT (tenant_id, kol_id) DO NOTHING",
        IdWorker.getId(),
        tenantId,
        kolId,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private BigDecimal lockBalance(long tenantId, long kolId) {
    return jdbc.queryForObject(
        "SELECT balance_cny FROM crm_commission_wallet WHERE tenant_id = ? AND kol_id = ? FOR UPDATE",
        BigDecimal.class,
        tenantId,
        kolId);
  }

  private BigDecimal existingEntry(
      long tenantId,
      long kolId,
      String sourceType,
      long sourceId,
      String sourceKey,
      BigDecimal amount) {
    List<ExistingEntry> existing =
        jdbc.query(
            "SELECT amount_cny, balance_after_cny, kol_id, source_type, source_id FROM crm_commission_ledger WHERE tenant_id = ? AND source_key = ?",
            (rs, index) ->
                new ExistingEntry(
                    rs.getBigDecimal(1),
                    rs.getBigDecimal(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getLong(5)),
            tenantId,
            sourceKey);
    if (existing.isEmpty()) {
      return null;
    }
    if (existing.getFirst().amount().compareTo(amount) != 0
        || existing.getFirst().kolId() != kolId
        || !existing.getFirst().sourceType().equals(sourceType)
        || existing.getFirst().sourceId() != sourceId) {
      throw new BusinessException(ErrorCode.PREPAY_REQUEST_CONFLICT);
    }
    return existing.getFirst().balanceAfter();
  }

  private static void validateCredit(CreditCommand command) {
    if (command == null
        || command.tenantId() <= 0
        || command.kolId() <= 0
        || command.sourceId() <= 0
        || command.sourceKey() == null
        || command.sourceKey().isBlank()
        || command.sourceKey().length() > 120
        || command.amountCny() == null
        || command.amountCny().signum() <= 0
        || command.amountCny().scale() > 2
        || command.amountCny().compareTo(new BigDecimal("999999999999.99")) > 0
        || command.occurredAt() == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
  }

  private Instant now() {
    return truncate(clock.instant());
  }

  private static Instant truncate(Instant value) {
    return value.truncatedTo(ChronoUnit.MILLIS);
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private record Outstanding(long ledgerId, BigDecimal remaining) {}

  private record ExistingEntry(
      BigDecimal amount, BigDecimal balanceAfter, long kolId, String sourceType, long sourceId) {}
}
