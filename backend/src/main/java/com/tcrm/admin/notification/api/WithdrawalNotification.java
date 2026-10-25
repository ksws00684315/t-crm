package com.tcrm.admin.notification.api;

/** Notification payload for one withdrawal workflow event, delivered by the outbox worker. */
public record WithdrawalNotification(
    long tenantId,
    long withdrawalId,
    long kolId,
    String kolCode,
    String applicationNo,
    String eventType) {}
