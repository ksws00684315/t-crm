package com.tcrm.admin.kol.api;

import java.util.List;
import java.util.Optional;

/**
 * Idle-KOL cleanup collaborators for the order module (task 25a / 29): config read, promoting-KOL
 * listing, lock/soft-delete, and visibility checks for manual cleanup.
 */
public interface KolCleanupApi {
  /** Current company's cleanup config; defaults to disabled / 90 days when no row exists. */
  KolCleanupConfigDTO getCleanupConfig();

  /**
   * Undeleted KOLs whose stored relation status is {@code PROMOTING}, ordered by id. {@code
   * lastPromotingAt} is the create time of the latest STATUS record whose after-json entered
   * PROMOTING; may be null. {@code fallbackIdleAt} is update_time or create_time for idle baseline
   * when there is no attributed order and no promoting record.
   */
  List<PromotingKolDTO> listPromotingWithLastPromotingAt();

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
  record CleanupKolSnapshot(long id, String kolCode, String name, Long bdUserId) {}
}
