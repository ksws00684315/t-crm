package com.tcrm.admin.kol.api;

/**
 * Published inside the transaction that changes a KOL's redirect target inputs (task 11 §4.4): the
 * agent platform template or the invite code. The link index listener registers an outbox row
 * before commit so both link hashes of that KOL are refreshed afterwards.
 *
 * @param kolId KOL ID
 */
public record KolLinkTargetChangedEvent(Long kolId) {}
