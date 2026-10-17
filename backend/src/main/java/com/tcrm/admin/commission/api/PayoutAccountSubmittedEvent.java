package com.tcrm.admin.commission.api;

/** A creator created or resubmitted a payout account, which is now PENDING. */
public record PayoutAccountSubmittedEvent(
    long tenantId, long kolId, long accountId, String kolCode, String accountType) {}
