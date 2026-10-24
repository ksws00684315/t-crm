package com.tcrm.admin.commission.model;

/**
 * One due outbox row joined to its prepay. Read across companies by the delivery worker; the join
 * pairs each event with the prepay of the same company.
 */
public record PrepayOutboxClaim(
    long id,
    long tenantId,
    long prepayId,
    String eventType,
    long kolId,
    long applicantId,
    String prepayNo) {}
