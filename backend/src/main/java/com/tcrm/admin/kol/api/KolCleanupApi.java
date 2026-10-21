package com.tcrm.admin.kol.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Idle-KOL cleanup collaborators for the order module (task 33): per-KOL thresholds, promoting-KOL
 * listing, lock/soft-delete, and visibility checks for manual cleanup.
 */
public interface KolCleanupApi {
  /** Sets this KOL's automatic cleanup threshold; null disables automatic cleanup. */
  void setAutoCleanupDays(long kolId, Integer days);

  /**
   * Undeleted KOLs whose stored relation status is {@code PROMOTING}, ordered by id. {@code
   * lastPromotingAt} is the create time of the latest STATUS record whose after-json entered
   * PROMOTING; may be null. {@code fallbackIdleAt} is update_time or create_time for idle baseline
   * when there is no attributed order and no promoting record.
   */
  List<PromotingKolDTO> listPromotingWithLastPromotingAt();

  /**
   * Same as {@link #listPromotingWithLastPromotingAt()} but the KOL query is restricted to the
   * given ids, so promotion records are only read for those KOLs. Page-scoped countdowns must use
   * this variant instead of loading every promoting KOL of the company.
   */
  List<PromotingKolDTO> listPromotingWithLastPromotingAt(Collection<Long> ids);

  /**
   * Locks the KOL and returns it when still PROMOTING (relation status). Empty when missing or not
   * promoting. Caller must be in a transaction.
   */
  Optional<CleanupKolSnapshot> lockPromotingKol(long kolId);

  /**
   * Locks any undeleted KOL for manual cleanup. Empty when missing. Caller must be in a
   * transaction.
   */
  Optional<CleanupKolSnapshot> lockKol(long kolId);

  /**
   * Logically deletes a previously locked KOL and publishes {@link KolStatusChangedEvent}. Returns
   * false when the row is already gone.
   */
  boolean softDeleteLockedKol(long kolId);

  /** Snapshot used while cleaning (before soft-delete). */
  record CleanupKolSnapshot(
      long id, String kolCode, String name, Long bdUserId, Integer autoCleanupDays) {}
}
