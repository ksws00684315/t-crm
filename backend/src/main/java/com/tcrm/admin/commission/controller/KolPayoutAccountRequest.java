package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.model.PayoutAccountType;
import com.tcrm.admin.commission.model.UsdtNetwork;
import com.tcrm.admin.commission.service.PayoutAccountService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * KOL-side write payload for a payout account (task 15a §4.2.1). Only the fields of the given type
 * are required; the per-type checks produce field errors in the request language.
 */
public record KolPayoutAccountRequest(
    @NotNull PayoutAccountType accountType,
    @NotNull @Size(min = 3, max = 128) String account,
    @Size(max = 64) String holderName,
    UsdtNetwork network,
    @Min(1) Long qrFileId) {
  private static final String ALNUM = "^[A-Za-z0-9]+$";

  @AssertTrue(message = "{tcrm.payout.paypal-fields}")
  private boolean isPayPalValid() {
    return accountType != PayoutAccountType.PAYPAL || (notBlank(account) && notBlank(holderName));
  }

  @AssertTrue(message = "{tcrm.payout.usdt-fields}")
  private boolean isUsdtValid() {
    return accountType != PayoutAccountType.USDT
        || (notBlank(account) && account.strip().matches(ALNUM) && network != null);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  PayoutAccountService.SaveCommand toCommand() {
    return new PayoutAccountService.SaveCommand(
        accountType, account.strip(), text(holderName), network, qrFileId);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
