package com.tcrm.admin.kol.api;

/**
 * Published inside the transaction that enables or disables a KOL (task 06 §4.3). Listeners should
 * react after commit.
 *
 * @param kolId KOL ID
 */
public record KolStatusChangedEvent(Long kolId) {}
