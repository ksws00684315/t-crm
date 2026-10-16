package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.service.PayoutAccountService;
import java.time.Instant;

/**
 * Account row for the admin list (task 15a §4.4): masked account only. {@code accountType} and
 * {@code status} are the enum names; ids serialize as strings like everywhere else.
 */
public record AdminPayoutAccountVO(
    Long id,
    Long kolId,
    String kolCode,
    String kolName,
    PayoutAccountType accountType,
    String accountMasked,
    String holderName,
    UsdtNetwork network,
    Long qrFileId,
    PayoutAccountStatus status,
    boolean defaultAccount,
    String rejectReason,
    Instant lastSubmittedAt,
    Instant reviewedTime) {
  static AdminPayoutAccountVO from(PayoutAccountService.AdminAccountDTO a) {
    return new AdminPayoutAccountVO(
        a.id(),
        a.kolId(),
        a.kolCode(),
        a.kolName(),
        a.accountType(),
        a.accountMasked(),
        a.holderName(),
        a.network(),
        a.qrFileId(),
        a.status(),
        a.defaultAccount(),
        a.rejectReason(),
        a.lastSubmittedAt(),
        a.reviewedTime());
  }
}
