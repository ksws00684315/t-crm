package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Parameter objects for conditional withdrawal writes. Company id is not a column here. */
public final class WithdrawalWrite {
  private WithdrawalWrite() {}

  public record Insert(
      long id,
      long kolId,
      String applicationNo,
      UUID requestId,
      String fingerprint,
      Long previousId,
      BigDecimal amount,
      Long accountId,
      String accountType,
      String accountCipher,
      String accountMasked,
      String accountCurrency,
      String accountNetwork,
      String accountHolder,
      Long accountQrFileId,
      String note,
      long actorId,
      Instant now) {}

  public record Allocation(
      long id,
      long withdrawalId,
      long orderItemId,
      String orderNo,
      String itemName,
      String skuName,
      String imageUrl,
      BigDecimal occupied,
      BigDecimal remainingSnapshot,
      BigDecimal sourceReleased,
      Instant createTime) {}

  public record Decide(
      long id, String next, String current, long reviewerId, Instant now, String reviewNote) {}

  public record Withdraw(long id, long kolId, Instant now) {}

  public record Paid(long id, String financeNote, long operatorId, Instant now) {}

  public record History(
      long id,
      long withdrawalId,
      String action,
      Long actorId,
      String actorName,
      String actorRole,
      String note,
      Instant createTime) {}

  public record Outbox(
      long id,
      long withdrawalId,
      String eventType,
      String payload,
      Instant nextAttemptAt,
      Instant createTime) {}

  public record Payment(
      long id,
      long withdrawalId,
      UUID requestId,
      String fingerprint,
      BigDecimal amount,
      BigDecimal actualAmount,
      String actualCurrency,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      long voucherFileId,
      long operatorId,
      String operatorName,
      String operatorRole,
      Instant createTime) {}

  public record Freeze(long id, long kolId, String reason, long frozenBy, Instant now) {}

  public record Resume(long id, String reason, long resumedBy, Instant now) {}
}
