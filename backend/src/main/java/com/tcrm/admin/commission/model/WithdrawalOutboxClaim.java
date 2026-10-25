package com.tcrm.admin.commission.model;

/**
 * One due withdrawal outbox row joined to its application and creator code. Read across companies
 * by the delivery worker; the joins pair each event with the rows of the same company.
 */
public record WithdrawalOutboxClaim(
    long id,
    long tenantId,
    long withdrawalId,
    String eventType,
    long kolId,
    String kolCode,
    String applicationNo) {}
