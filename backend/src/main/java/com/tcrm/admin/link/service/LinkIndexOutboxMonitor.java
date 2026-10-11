package com.tcrm.admin.link.service;

import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a minute, reports a link-index outbox that is falling behind (task 06b). Waits are measured
 * from the oldest {@code create_time}. A wait above 1 minute is WARN and above 10 minutes is ERROR;
 * a row with 5 or more attempts is always ERROR. Empty tables produce no wait log.
 */
@Component
public class LinkIndexOutboxMonitor {
  static final String LOCK = "link:index-outbox-monitor";
  static final Duration WARN_AFTER = Duration.ofMinutes(1);
  static final Duration ERROR_AFTER = Duration.ofMinutes(10);
  static final int ATTEMPTS_ERROR = 5;

  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexOutboxMonitor.class);

  private final LinkIndexOutboxMapper outbox;
  private final DistributedLocks locks;
  private final Clock clock;

  /** Severity of the oldest unprocessed row. */
  enum Level {
    OK,
    WARN,
    ERROR
  }

  @Autowired
  public LinkIndexOutboxMonitor(LinkIndexOutboxMapper outbox, DistributedLocks locks) {
    this(outbox, locks, Clock.systemUTC());
  }

  LinkIndexOutboxMonitor(LinkIndexOutboxMapper outbox, DistributedLocks locks, Clock clock) {
    this.outbox = outbox;
    this.locks = locks;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void scheduled() throws InterruptedException {
    locks.run(LOCK, 0, 50, this::check);
  }

  void check() {
    TenantScope.runSystem("read link index outbox lag", this::checkInScope);
  }

  private void checkInScope() {
    Instant oldest = outbox.oldestCreateTime();
    if (oldest != null) {
      logWait(Duration.between(oldest, clock.instant()));
    }
    for (LinkIndexOutboxDO row : outbox.listAttemptsAtLeast(ATTEMPTS_ERROR)) {
      LOG.error(
          "Link index outbox {} {} has {} attempts",
          row.getScope(),
          row.getId(),
          row.getAttempts());
    }
  }

  /** ERROR above 10 minutes, WARN above 1 minute; the two bands do not overlap. */
  static Level waitLevel(Duration wait) {
    if (wait.compareTo(ERROR_AFTER) > 0) {
      return Level.ERROR;
    }
    if (wait.compareTo(WARN_AFTER) > 0) {
      return Level.WARN;
    }
    return Level.OK;
  }

  private static void logWait(Duration wait) {
    long seconds = wait.toSeconds();
    switch (waitLevel(wait)) {
      case ERROR -> LOG.error("Link index outbox oldest row has waited {}s", seconds);
      case WARN -> LOG.warn("Link index outbox oldest row has waited {}s", seconds);
      default -> LOG.debug("Link index outbox oldest row has waited {}s", seconds);
    }
  }
}
