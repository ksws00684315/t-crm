package com.tcrm.admin.product.api;

import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cleanup detail rows (task 29 / ADR-025). Order orchestrates cleanup; product owns the table and
 * the background Weidian-delete worker.
 */
public interface KolCleanupItemApi {
  /**
   * Creates PENDING rows for each current exclusive-item mapping of the KOL. {@code bdUserId} is a
   * snapshot for data-scope filtering after the KOL is soft-deleted.
   */
  List<Long> createPendingItems(
      long kolId,
      String kolCode,
      Long bdUserId,
      String triggerType,
      String reason,
      Instant cleanupTime,
      Long actorId);

  /**
   * Enqueues background deletes for PENDING rows of this KOL. Returns immediately; the HTTP caller
   * must not wait for Weidian.
   */
  void enqueueDeletesForKol(long kolId);

  /**
   * Atomically moves one FAILED row back to PENDING (affected rows must be 1) and enqueues
   * background delete. The API returns success while the row still shows as pending until the
   * worker finishes.
   */
  void retryFailed(long cleanupItemId);

  /** Returns the row or empty when missing. */
  Optional<KolCleanupItemDTO> getById(long id);

  PageResult<KolCleanupItemDTO> page(
      String kolCode, String status, Long bdUserIdScope, int page, int size);

  record KolCleanupItemDTO(
      Long id,
      Long kolId,
      String kolCode,
      Long bdUserId,
      Long kolItemId,
      Long shopId,
      String shopName,
      String weidianItemId,
      String status,
      String trigger,
      String reason,
      String failReason,
      Instant cleanupTime,
      Instant finishTime) {}
}
