package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PayoutAccountService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Review decision (task 15a §4.4): REJECT requires a reason of at most 255 characters. */
public record ReviewRequest(
    @NotNull @Pattern(regexp = "APPROVE|REJECT") String action, @Size(max = 255) String reason) {

  @AssertTrue(message = "{tcrm.payout.reject-reason-required}")
  private boolean isReasonPresentWhenRejecting() {
    return !"REJECT".equals(action) || (reason != null && !reason.isBlank());
  }

  PayoutAccountService.ReviewAction toAction() {
    return PayoutAccountService.ReviewAction.valueOf(action);
  }

  @NotBlank
  String normalizedReason() {
    return reason == null ? null : reason.strip();
  }
}
