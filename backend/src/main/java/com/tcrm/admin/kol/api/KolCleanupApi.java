package com.tcrm.admin.kol.api;

import java.util.List;

/**
 * Idle-KOL cleanup collaborators for the order module (task 25a architecture review): config read,
 * promoting-KOL listing, and logical delete. Config write/preview HTTP stay on kol controllers;
 * orchestration lives in order.
 */
public interface KolCleanupApi {
  /** Current company's cleanup config; defaults to disabled / 90 days when no row exists. */
  KolCleanupConfigDTO getCleanupConfig();

  /**
   * Undeleted KOLs whose stored promotion status is {@code PROMOTING}, ordered by id. {@code
   * lastPromotingAt} is the create time of the latest STATUS record whose after-json entered
   * PROMOTING; may be null. {@code fallbackIdleAt} is update_time or create_time for idle baseline
   * when there is no attributed order and no promoting record.
   */
  List<PromotingKolDTO> listPromotingWithLastPromotingAt();

  /**
   * Locks the KOL, verifies it is still PROMOTING, logically deletes it, and publishes {@link
   * KolStatusChangedEvent}. Returns false when the row is missing or no longer PROMOTING.
   */
  boolean softDeletePromotingKol(long kolId);
}
