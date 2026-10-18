package com.tcrm.admin.product.api;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a confirmed promo-sheet upload (task 26 §3.3): the classification was re-run at
 * confirmation time, so the numbers reflect the moment of the write.
 *
 * @param inserted mappings written with source {@code SHEET_UPLOAD}
 * @param duplicates skipped later occurrences of shop items the sheet already listed
 * @param existing shop items already mapped to this KOL
 * @param conflicts shop items currently mapped to another KOL; they were not overwritten
 * @param notInSheet current mappings of this KOL whose shop item did not appear in the sheet
 * @param skipCounts skip reasons with their occurrence counts
 * @param conflictItems the conflicts for display; owner fields are for super admins only
 */
public record PromoSheetConfirmResult(
    int inserted,
    int duplicates,
    int existing,
    int conflicts,
    int notInSheet,
    Map<PromoSheetSkipReason, Integer> skipCounts,
    List<PromoSheetClassification.ConflictItem> conflictItems) {}
