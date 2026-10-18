package com.tcrm.admin.product.api;

/**
 * Published inside the transaction that bulk-adds mappings of one KOL (task 26 sheet upload).
 * Unlike {@link KolItemChangedEvent} it does not name an original product — a promo-sheet mapping
 * may have none — so the link index listener registers a KOL-scope refresh that rewrites every
 * current field, including the {@code linkitem} fields of mappings without an original product.
 *
 * @param kolId KOL ID
 */
public record KolMappingsChangedEvent(Long kolId) {}
