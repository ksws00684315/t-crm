package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.service.SettlementPullPlanner.Action;
import com.tcrm.admin.order.service.SettlementPullPlanner.Window;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests of the settlement pull due-check and window splitting (task 76 §3.4). */
class SettlementPullPlannerTest {
  // 2026-10-28 19:30 UTC = 2026-10-29 03:30 Beijing.
  private static final Instant NOW = Instant.parse("2026-10-28T19:30:00Z");

  @Test
  void shouldDueIncrementalWhenIntervalElapsed() {
    Instant lastPull = NOW.minus(Duration.ofMinutes(60));

    assertThat(SettlementPullPlanner.decide(NOW, 60, 3, lastPull, NOW))
        .isEqualTo(Action.INCREMENTAL);
    // One minute short of the interval: not due yet.
    assertThat(SettlementPullPlanner.decide(NOW, 60, 3, lastPull.plus(Duration.ofMinutes(1)), NOW))
        .isEqualTo(Action.NONE);
    // Never pulled and never backfilled past the hour: the backfill wins (it covers the window).
    assertThat(SettlementPullPlanner.decide(NOW, 15, 3, null, null)).isEqualTo(Action.BACKFILL);
  }

  @Test
  void shouldDueBackfillOncePerBeijingDayAfterConfiguredHour() {
    // NOW is Beijing 03:30 with the default hour 3: due on a new Beijing day.
    assertThat(SettlementPullPlanner.decide(NOW, 60, 3, NOW, NOW.minus(Duration.ofDays(1))))
        .isEqualTo(Action.BACKFILL);
    // Already backfilled today (Beijing): falls back to the incremental rule.
    assertThat(SettlementPullPlanner.decide(NOW, 60, 3, NOW, NOW)).isEqualTo(Action.NONE);

    // Beijing hour 2 with backfill-hour 3: the backfill is not due yet, so a due incremental
    // still runs (interval elapsed 61 minutes ago).
    Instant beijingTwo = Instant.parse("2026-10-28T18:30:00Z");
    assertThat(
            SettlementPullPlanner.decide(
                beijingTwo, 60, 3, beijingTwo.minus(Duration.ofMinutes(61)), beijingTwo))
        .isEqualTo(Action.INCREMENTAL);

    // Hour 0 edge: at Beijing 23:50 the day is not over; at 00:10 the next day is due.
    Instant lateNight = Instant.parse("2026-10-28T15:50:00Z"); // Beijing 23:50
    assertThat(SettlementPullPlanner.decide(lateNight, 60, 0, lateNight, lateNight))
        .isEqualTo(Action.NONE);
    Instant afterMidnight = Instant.parse("2026-10-28T16:10:00Z"); // Beijing next day 00:10
    assertThat(SettlementPullPlanner.decide(afterMidnight, 60, 0, lateNight, lateNight))
        .isEqualTo(Action.BACKFILL);
    // Never backfilled with hour 0: due as soon as the Beijing day starts.
    assertThat(SettlementPullPlanner.decide(afterMidnight, 60, 0, afterMidnight, null))
        .isEqualTo(Action.BACKFILL);
  }

  @Test
  void shouldSplitWindowsToAtMost89Days() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant end = Instant.parse("2026-06-01T00:00:00Z"); // 151 days

    List<Window> windows = SettlementPullPlanner.windows(start, end);

    // Hand-check: Jan 1 + 89 days = Mar 31 (31 + 28 + 30); the remainder Mar 31 -> Jun 1 is 62
    // days, so two windows and no window exceeds 89 days.
    assertThat(windows).hasSize(2);
    assertThat(windows.get(0).start()).isEqualTo(start);
    assertThat(windows.get(0).end()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
    assertThat(windows.get(1).start()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
    assertThat(windows.get(1).end()).isEqualTo(end);
    for (Window window : windows) {
      assertThat(Duration.between(window.start(), window.end()).toDays()).isLessThanOrEqualTo(89);
    }

    // An 89-day backfill range stays a single window; short ranges are not split.
    List<Window> backfill =
        SettlementPullPlanner.windows(end.minus(SettlementPullPlanner.BACKFILL_LOOKBACK), end);
    assertThat(backfill).hasSize(1);
    assertThat(Duration.between(backfill.get(0).start(), backfill.get(0).end()).toDays())
        .isEqualTo(89);
    List<Window> three = SettlementPullPlanner.windows(end.minus(Duration.ofDays(3)), end);
    assertThat(three).hasSize(1);
  }
}
