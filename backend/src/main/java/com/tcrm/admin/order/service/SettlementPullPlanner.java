package com.tcrm.admin.order.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure scheduling decisions for the settlement flow pull (task 76 §3.4). The coordinator polls on a
 * fixed short cadence and asks this class whether a shop is due, reading the intervals from the
 * platform system configuration each round so changes apply without a restart.
 */
final class SettlementPullPlanner {
  /** Incremental pulls always look back three days (late and corrected flows included). */
  static final Duration INCREMENTAL_LOOKBACK = Duration.ofDays(3);

  /**
   * Daily backfill lookback. Task 75 measured that a full three calendar months is rejected with
   * error -1; windows of at most {@link #MAX_WINDOW_DAYS} days succeeded.
   */
  static final Duration BACKFILL_LOOKBACK = Duration.ofDays(89);

  /** Hard window limit; the splitter never produces a longer window. */
  static final int MAX_WINDOW_DAYS = 89;

  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

  private SettlementPullPlanner() {}

  /** What a shop is due for right now. */
  enum Action {
    NONE,
    INCREMENTAL,
    BACKFILL
  }

  /**
   * Decides the due action. The backfill runs once per Beijing day once the configured hour has
   * passed (a backfill also counts as the round's incremental pull, since it covers its window);
   * otherwise an incremental pull is due when the configured interval elapsed.
   */
  static Action decide(
      Instant now,
      int intervalMinutes,
      int backfillHour,
      Instant lastIncremental,
      Instant lastBackfill) {
    int hour = now.atZone(BEIJING).getHour();
    if (hour >= backfillHour && differentBeijingDay(now, lastBackfill)) {
      return Action.BACKFILL;
    }
    if (lastIncremental == null
        || !now.isBefore(lastIncremental.plus(Duration.ofMinutes(intervalMinutes)))) {
      return Action.INCREMENTAL;
    }
    return Action.NONE;
  }

  private static boolean differentBeijingDay(Instant now, Instant last) {
    if (last == null) {
      return true;
    }
    return !now.atZone(BEIJING).toLocalDate().equals(last.atZone(BEIJING).toLocalDate());
  }

  /** Splits {@code [start, end)} into windows of at most {@link #MAX_WINDOW_DAYS} days. */
  static List<Window> windows(Instant start, Instant end) {
    List<Window> result = new ArrayList<>();
    Instant windowStart = start;
    while (windowStart.isBefore(end)) {
      Instant windowEnd = windowStart.plus(Duration.ofDays(MAX_WINDOW_DAYS));
      if (!windowEnd.isBefore(end)) {
        windowEnd = end;
      }
      result.add(new Window(windowStart, windowEnd));
      windowStart = windowEnd;
    }
    return result;
  }

  /** One query window; {@code end} is exclusive, matching the API's millisecond range. */
  record Window(Instant start, Instant end) {}
}
