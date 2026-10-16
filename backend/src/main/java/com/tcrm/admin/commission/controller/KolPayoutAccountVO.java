package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.model.PayoutAccountStatus;
import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.service.PayoutAccountService;
import java.time.Instant;

/**
 * One of the KOL's own accounts: the full account is visible to its owner only (task 15a §4.3);
 * every other reader gets the masked value.
 */
public record KolPayoutAccountVO(
    Long id,
    PayoutAccountType accountType,
    String account,
    String holderName,
    UsdtNetwork network,
    Long qrFileId,
    PayoutAccountStatus status,
    boolean defaultAccount,
    String rejectReason,
    Instant lastSubmittedAt,
    Instant reviewedTime) {
  static KolPayoutAccountVO from(PayoutAccountService.KolAccountDTO a) {
    return new KolPayoutAccountVO(
        a.id(),
        a.accountType(),
        a.account(),
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
