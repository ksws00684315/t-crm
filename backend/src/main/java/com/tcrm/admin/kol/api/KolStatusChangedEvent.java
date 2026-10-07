package com.tcrm.admin.kol.api;

/**
 * Published inside the transaction that enables or disables a KOL (task 06 §4.3). The link index
 * listener registers an outbox row before commit.
 *
 * @param kolId KOL ID
 */
public record KolStatusChangedEvent(Long kolId) {}
