package com.tcrm.admin.commission.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Read-side rows for withdrawal allocations, history, payments and freeze state. */
public final class WithdrawalRows {
  private WithdrawalRows() {}

  /** One FIFO allocation row with its immutable display snapshot. */
  public record AllocationRow(
      long id,
      long orderItemId,
      String orderNo,
      String itemName,
      String skuName,
      String imageUrl,
      BigDecimal occupiedCny,
      BigDecimal remainingSnapshotCny,
      BigDecimal sourceReleasedCny) {}

  /** One history line; the note is filtered by role visibility in the service. */
  public record HistoryRow(
      String action, Long actorId, String actorName, String actorRole, String note, Instant at) {}

  /** The single payment fact of a paid withdrawal. */
  public record PaymentRow(
      BigDecimal amountCny,
      BigDecimal actualAmount,
      String actualCurrency,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      Long voucherFileId,
      Long operatorId,
      String operatorName,
      String operatorRole) {}

  /** Active or latest freeze row of one creator. */
  public record FreezeRow(
      long id,
      long kolId,
      String reason,
      long frozenBy,
      Instant frozenTime,
      String resumeReason,
      Long resumedBy,
      Instant resumedTime) {
    public boolean active() {
      return resumedTime == null;
    }
  }
}
