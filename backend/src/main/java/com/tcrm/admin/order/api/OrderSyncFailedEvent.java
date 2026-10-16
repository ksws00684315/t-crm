package com.tcrm.admin.order.api;

/** A shop's order synchronization went from success to failure. */
public record OrderSyncFailedEvent(long tenantId, long shopId, String shopName) {}
