package com.tcrm.admin.product.api;

import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.List;

/**
 * Cleanup detail rows (task 29 / ADR-025). Order orchestrates cleanup; product owns the table and
 * Weidian delete worker.
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

  /** Processes all PENDING rows for a KOL created at this cleanup time. */
  void processAllPendingForKol(long kolId, Instant cleanupTime);

  /** Retries one FAILED row (Weidian delete once; no business auto-retry). */
  void retryFailed(long cleanupItemId);

  /** Returns the row or empty when missing. */
  java.util.Optional<KolCleanupItemDTO> getById(long id);

  PageResult<KolCleanupItemDTO> page(
      String kolCode, String status, Long bdUserIdScope, int page, int size);

  record KolCleanupItemDTO(
      Long id,
      Long kolId,
      String kolCode,
      Long bdUserId,
      Long kolItemId,
      Long shopId,
      String weidianItemId,
      String status,
      String trigger,
      String reason,
      String failReason,
      Instant cleanupTime,
      Instant finishTime) {}
}
