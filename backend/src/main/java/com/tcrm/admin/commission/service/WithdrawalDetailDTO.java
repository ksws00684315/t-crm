package com.tcrm.admin.commission.service;

import java.time.Instant;
import java.util.List;

/** Historical display values, not a second wallet or a withdrawal write model. */
public record WithdrawalDetailDTO(
    String id,
    String applicationNo,
    String kolId,
    String kolName,
    String kolCode,
    String bdId,
    String bdName,
    String type,
    String amount,
    String currency,
    String status,
    Instant createdAt,
    boolean paused,
    String pauseReason,
    Account account,
    String note,
    String reviewNote,
    String operationsNote,
    String financeNote,
    List<Allocation> allocations,
    List<History> history,
    Payment payment,
    String previousId) {
  public record Account(
      String type, String account, String holder, String currency, String network) {}

  public record History(String action, Instant at, String actor, String actorId, String note) {}

  public record Payment(
      String actualAmount,
      String actualCurrency,
      String amountCny,
      Instant paidAt,
      String method,
      String financeNote,
      String conversionNote,
      String voucher) {}

  public record Product(String id, String image, String product, String sku) {}

  public record Allocation(
      String id,
      String orderNo,
      String image,
      String product,
      String sku,
      String occupied,
      String remainingSnapshot,
      String orderRemainingSnapshot) {}

  public record Order(
      String id,
      String orderNo,
      List<Product> products,
      String occupied,
      String remainingSnapshot) {}

  public WithdrawalDetailDTO withIdentityAndAccount(
      String storedId, String storedKolId, Account recipient, boolean financialRemarks) {
    var visibleHistory =
        history == null
            ? List.<History>of()
            : history.stream()
                .map(
                    item ->
                        !financialRemarks
                                && ("PAID".equals(item.action())
                                    || "PENDING_PAYMENT".equals(item.action())
                                    || (item.action() != null
                                        && item.action().startsWith("FINANCE_")))
                            ? new History(
                                item.action(), item.at(), item.actor(), item.actorId(), null)
                            : item)
                .toList();
    var visiblePayment =
        payment == null || financialRemarks
            ? payment
            : new Payment(
                payment.actualAmount(),
                payment.actualCurrency(),
                payment.amountCny(),
                payment.paidAt(),
                payment.method(),
                null,
                payment.conversionNote(),
                payment.voucher());
    return new WithdrawalDetailDTO(
        storedId,
        applicationNo,
        storedKolId,
        kolName,
        kolCode,
        bdId,
        bdName,
        "COMMISSION",
        amount,
        "CNY",
        status,
        createdAt,
        paused,
        pauseReason,
        recipient,
        note,
        financialRemarks ? reviewNote : null,
        operationsNote,
        financialRemarks ? financeNote : null,
        List.of(),
        visibleHistory,
        visiblePayment,
        previousId);
  }
}
