package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PrepayService;

/** Masked payout snapshot shown on admin prepay detail. */
public record PrepayPayoutOptionVO(
    Long id,
    String type,
    String currency,
    String network,
    String maskedAccount,
    String holderName) {
  static PrepayPayoutOptionVO from(PrepayService.PayoutOption option) {
    if (option == null) {
      return null;
    }
    return new PrepayPayoutOptionVO(
        option.id(),
        option.type(),
        option.currency(),
        option.network(),
        option.maskedAccount(),
        option.holderName());
  }
}
