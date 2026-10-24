package com.tcrm.admin.commission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.model.LedgerSourceRow;
import com.tcrm.admin.commission.model.OutstandingDeduction;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Production write service for commission wallet balances and immutable ledger entries. */
@Service
public class CommissionWalletService {
  private final CommissionWalletMapper wallets;
  private final KolApi kols;
  private final SystemConfigApi configs;
  private final Clock clock;

  @Autowired
  public CommissionWalletService(
      CommissionWalletMapper wallets, KolApi kols, SystemConfigApi configs) {
    this(wallets, kols, configs, Clock.systemUTC());
  }

  CommissionWalletService(
      CommissionWalletMapper wallets, KolApi kols, SystemConfigApi configs, Clock clock) {
    this.wallets = wallets;
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
   * Posts a negative commission adjustment (design §8.3 / ADR-032 §1): released commission that a
   * refund or attribution correction clawed back. The balance may go negative; later credits refill
   * it through the FIFO recovery of {@link #postCommissionCredit}.
   */
  @Transactional(rollbackFor = Exception.class)
  public BigDecimal postCommissionAdjustment(
      long tenantId,
      long kolId,
      long sourceId,
      String sourceKey,
      BigDecimal negativeAmount,
      Instant occurredAt) {
    if (negativeAmount == null || negativeAmount.signum() >= 0 || negativeAmount.scale() > 2) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        tenantId,
        () -> {
          ensureWallet(kolId);
          BigDecimal balance = lockBalance(kolId);
          BigDecimal existing =
              existingEntry(kolId, "COMMISSION", sourceId, sourceKey, negativeAmount);
          if (existing != null) {
            return existing;
          }
          BigDecimal next = balance.add(negativeAmount).setScale(2, RoundingMode.UNNECESSARY);
          wallets.insertLedger(
              IdWorker.getId(),
              kolId,
              "COMMISSION_ADJUSTMENT",
              negativeAmount,
              "COMMISSION",
              sourceId,
              sourceKey,
              truncate(occurredAt),
              next,
              null,
              now());
          wallets.updateBalance(kolId, next, now());
          return next;
        });
  }

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
          ensureWallet(kolId);
          BigDecimal balance = lockBalance(kolId);
          String sourceKey = "PREPAY_DEDUCT:" + prepayId;
          BigDecimal existing =
              existingEntry(kolId, "PREPAY", prepayId, sourceKey, amount.negate());
          if (existing != null) {
            return existing;
          }
          BigDecimal next = balance.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
          Instant time = truncate(occurredAt);
          wallets.insertLedger(
              IdWorker.getId(),
              kolId,
              "PREPAY_DEDUCT",
              amount.negate(),
              "PREPAY",
              prepayId,
              sourceKey,
              time,
              next,
              operatorId,
              now());
          wallets.updateBalance(kolId, next, now());
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
    return TenantScope.callCompany(
        tenantId,
        () -> {
          BigDecimal balance = wallets.findBalance(kolId);
          return balance == null ? BigDecimal.ZERO.setScale(2) : balance;
        });
  }

  /** Read-only ledger lookup by idempotency source key; null when nothing was posted yet. */
  @Transactional(readOnly = true)
  public LedgerEntry findEntry(long tenantId, String sourceKey) {
    if (sourceKey == null || sourceKey.isBlank()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return TenantScope.callCompany(
        tenantId,
        () -> {
          LedgerSourceRow row = wallets.findBySourceKey(sourceKey);
          return row == null
              ? null
              : new LedgerEntry(
                  row.kolId(),
                  row.sourceType(),
                  row.sourceId(),
                  row.amountCny(),
                  row.balanceAfterCny());
        });
  }

  /** Posted ledger entry of one source key. */
  public record LedgerEntry(
      long kolId,
      String sourceType,
      long sourceId,
      BigDecimal amountCny,
      BigDecimal balanceAfterCny) {}

  private BigDecimal postCredit(CreditCommand command) {
    ensureWallet(command.kolId());
    BigDecimal balance = lockBalance(command.kolId());
    BigDecimal amount = command.amountCny().setScale(2, RoundingMode.UNNECESSARY);
    BigDecimal existing =
        existingEntry(
            command.kolId(), "COMMISSION", command.sourceId(), command.sourceKey(), amount);
    if (existing != null) {
      return existing;
    }
    BigDecimal next = balance.add(amount).setScale(2, RoundingMode.UNNECESSARY);
    Instant now = now();
    long creditId = IdWorker.getId();
    wallets.insertLedger(
        creditId,
        command.kolId(),
        "COMMISSION_CREDIT",
        amount,
        "COMMISSION",
        command.sourceId(),
        command.sourceKey(),
        truncate(command.occurredAt()),
        next,
        null,
        now);
    allocateFifo(command, creditId, amount);
    wallets.updateBalance(command.kolId(), next, now);
    return next;
  }

  private void allocateFifo(CreditCommand command, long creditId, BigDecimal credit) {
    List<OutstandingDeduction> outstanding = wallets.listOutstanding(command.kolId());
    BigDecimal remainingCredit = credit;
    for (OutstandingDeduction deduction : outstanding) {
      if (remainingCredit.signum() == 0) {
        break;
      }
      BigDecimal amount = remainingCredit.min(deduction.remaining());
      wallets.insertRecovery(
          IdWorker.getId(), command.kolId(), creditId, deduction.ledgerId(), amount, now());
      remainingCredit = remainingCredit.subtract(amount);
    }
  }

  private void ensureWallet(long kolId) {
    wallets.insertIgnore(IdWorker.getId(), kolId, now());
  }

  private BigDecimal lockBalance(long kolId) {
    BigDecimal balance = wallets.lockBalance(kolId);
    if (balance == null) {
      throw new EmptyResultDataAccessException(1);
    }
    return balance;
  }

  private BigDecimal existingEntry(
      long kolId, String sourceType, long sourceId, String sourceKey, BigDecimal amount) {
    LedgerSourceRow existing = wallets.findBySourceKey(sourceKey);
    if (existing == null) {
      return null;
    }
    if (existing.amountCny().compareTo(amount) != 0
        || existing.kolId() != kolId
        || !existing.sourceType().equals(sourceType)
        || existing.sourceId() != sourceId) {
      throw new BusinessException(ErrorCode.PREPAY_REQUEST_CONFLICT);
    }
    return existing.balanceAfterCny();
  }

  private static void validateCredit(CreditCommand command) {
    if (command == null
        || command.tenantId() <= 0
        || command.kolId() <= 0
        || command.sourceId() <= 0
        || command.sourceKey() == null
        || command.sourceKey().isBlank()
        || command.sourceKey().length() > 160
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
}
