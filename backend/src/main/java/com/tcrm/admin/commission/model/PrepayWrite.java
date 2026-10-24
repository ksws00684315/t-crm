package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Parameter objects for conditional prepay writes. Company id is not a column here. */
public final class PrepayWrite {
  private PrepayWrite() {}

  public record Insert(
      long id,
      long kolId,
      String prepayNo,
      UUID requestId,
      long applicantId,
      BigDecimal amount,
      long actorId,
      Instant now) {}

  public record Details(
      long id,
      String reason,
      LocalDate expectedPayDate,
      boolean deductFromCommission,
      Long payoutAccountId,
      String payoutAccountType,
      String payoutCurrency,
      String payoutNetwork,
      String payoutHolderName,
      String payoutAccountCipher,
      String payoutAccountMasked,
      long actorId,
      Instant now) {}

  public record Decision(
      long id, String next, String current, long actorId, Instant now, String reason) {}

  public record Cancel(long id, long actorId, Instant now, String reason) {}

  public record Paid(long id, long actorId, Instant now) {}

  public record History(
      long id,
      long prepayId,
      String action,
      long operatorId,
      String reason,
      Instant createTime,
      String operatorName,
      String operatorRole) {}

  public record Outbox(
      long id,
      long prepayId,
      String eventType,
      String payload,
      Instant nextAttemptAt,
      Instant createTime) {}

  public record Payment(
      long id,
      long prepayId,
      UUID requestId,
      String fingerprint,
      BigDecimal amount,
      Instant payTime,
      String method,
      String remark,
      long voucherFileId,
      long operatorId,
      Instant createTime,
      String operatorName,
      String operatorRole) {}
}
