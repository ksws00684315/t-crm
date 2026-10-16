package com.tcrm.admin.commission.controller;

import com.tcrm.admin.commission.service.PayoutAccountService;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Review decision (task 15a §4.4, review fix): REJECT requires a reason of at most 255 characters,
 * and {@code lastSubmittedAt} carries the version the reviewer saw, exactly as returned by the list
 * or detail endpoint; a mismatch is a 409.
 */
public record ReviewRequest(
    @NotNull @Pattern(regexp = "APPROVE|REJECT") String action,
    @Size(max = 255) String reason,
    @NotNull Instant lastSubmittedAt) {

  @AssertTrue(message = "{tcrm.payout.reject-reason-required}")
  private boolean isReasonPresentWhenRejecting() {
    return !"REJECT".equals(action) || (reason != null && !reason.isBlank());
  }

  PayoutAccountService.ReviewAction toAction() {
    return PayoutAccountService.ReviewAction.valueOf(action);
  }

  String normalizedReason() {
    return reason == null ? null : reason.strip();
  }
}
