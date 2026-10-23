package com.tcrm.admin.notification.api;

/** Small immutable notification event; contains no money, account, or other private details. */
public record PrepayNotification(
    long tenantId,
    long prepayId,
    long kolId,
    long applicantId,
    String prepayNo,
    String eventType) {}
