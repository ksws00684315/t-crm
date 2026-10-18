package com.tcrm.admin.product.api;

import java.util.List;
import java.util.Map;

/**
 * Classification of one promo sheet against the database (task 26 §3.3). Counts and lists come from
 * the same pass, so they always agree.
 *
 * @param linkRows rows that carried a link candidate, in-sheet jumps included
 * @param duplicates later occurrences of a shop item the sheet already listed
 * @param toAdd shop items not currently mapped to any KOL
 * @param existing shop items already mapped to this KOL
 * @param conflicts shop items currently mapped to another KOL; they are not overwritten
 * @param notInSheet current mappings of this KOL whose shop item did not appear in the sheet
 * @param skipCounts skip reasons with their occurrence counts
 * @param skipSamples up to 50 samples of skipped rows (worksheet, row, reason)
 * @param newItems the {@link #toAdd} items with shop, item ID, name snapshot and product code
 * @param conflictItems the {@link #conflicts} items; the owner fields are for super admins only
 */
public record PromoSheetClassification(
    int linkRows,
    int duplicates,
    int toAdd,
    int existing,
    int conflicts,
    int notInSheet,
    Map<PromoSheetSkipReason, Integer> skipCounts,
    List<SkipSample> skipSamples,
    List<NewItem> newItems,
    List<ConflictItem> conflictItems) {

  /** A skipped row with its location; at most 50 are kept. */
  public record SkipSample(String sheet, int row, PromoSheetSkipReason reason) {}

  /**
   * A mapping that would be created on confirmation.
   *
   * @param shopName registered shop's display name
   * @param shopWeidianId Weidian shop number parsed from the link
   * @param weidianItemId copied Weidian item ID
   * @param itemName name snapshot from the sheet; null when the sheet has none
   * @param productCode original product code when one exists for the shop item; null otherwise
   */
  public record NewItem(
      String shopName,
      String shopWeidianId,
      String weidianItemId,
      String itemName,
      String productCode) {}

  /**
   * A shop item that currently belongs to another KOL.
   *
   * @param ownerKolCode owner's KOL code; the controller nulls it for non-super-admins
   * @param ownerKolName owner's display name; the controller nulls it for non-super-admins
   */
  public record ConflictItem(
      String shopWeidianId,
      String weidianItemId,
      String itemName,
      String ownerKolCode,
      String ownerKolName) {}
}
