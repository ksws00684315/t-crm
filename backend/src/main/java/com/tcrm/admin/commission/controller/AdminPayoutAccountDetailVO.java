package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.service.PayoutAccountService;
import java.time.Instant;

/**
 * Reviewer detail (task 15a §4.4): the decrypted account plus the QR file id, for accounts with
 * {@code payout-account:review} only. Every view lands in the operation log.
 */
public record AdminPayoutAccountDetailVO(
    Long id,
    Long kolId,
    String kolCode,
    String kolName,
    PayoutAccountType accountType,
    String account,
    String holderName,
    String currency,
    UsdtNetwork network,
    Long qrFileId,
    PayoutAccountStatus status,
    boolean defaultAccount,
    String rejectReason,
    Instant lastSubmittedAt,
    Instant reviewedTime) {
  static AdminPayoutAccountDetailVO from(PayoutAccountService.AdminAccountDetailDTO a) {
    return new AdminPayoutAccountDetailVO(
        a.id(),
        a.kolId(),
        a.kolCode(),
        a.kolName(),
        a.accountType(),
        a.account(),
        a.holderName(),
        a.currency(),
        a.network(),
        a.qrFileId(),
        a.status(),
        a.defaultAccount(),
        a.rejectReason(),
        a.lastSubmittedAt(),
        a.reviewedTime());
  }
}
