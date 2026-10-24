package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Full withdrawal row as stored (accountCipher 只在解密方使用，不进接口出参). */
public record WithdrawalRow(
    long id,
    long kolId,
    String applicationNo,
    UUID requestId,
    String fingerprint,
    Long previousId,
    BigDecimal amountCny,
    String status,
    boolean paused,
    String pauseReason,
    Long accountId,
    String accountType,
    String accountCipher,
    String accountMasked,
    String accountCurrency,
    String accountNetwork,
    String accountHolder,
    Long accountQrFileId,
    String note,
    String reviewNote,
    String financeNote,
    Long reviewerId,
    Instant reviewedAt,
    long actorId,
    Instant createTime) {

  /** Identity columns for list pages and idempotency lookups. */
  public record Head(
      long id,
      long kolId,
      String applicationNo,
      BigDecimal amountCny,
      String status,
      long actorId,
      Instant createTime) {}
}
