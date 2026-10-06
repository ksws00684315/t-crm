package com.tcrm.admin.link.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs the full link index rebuild (task 06 §4.3) in the background: daily at 04:30 UTC and on
 * demand by SUPER_ADMIN. The distributed lock guarantees that only one rebuild runs across
 * instances; the application never rebuilds on startup.
 */
@Service
public class LinkRebuildService {
  static final String LOCK = "link:index-rebuild";
  private static final Logger LOG = LoggerFactory.getLogger(LinkRebuildService.class);
  private final LinkIndexService index;
  private final DistributedLocks locks;
  private final ExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean();

  @Autowired
  public LinkRebuildService(LinkIndexService index, DistributedLocks locks) {
    this(
        index,
        locks,
        Executors.newSingleThreadExecutor(
            work -> {
              Thread thread = new Thread(work, "link-rebuild");
              thread.setDaemon(true);
              return thread;
            }));
  }

  LinkRebuildService(LinkIndexService index, DistributedLocks locks, ExecutorService executor) {
    this.index = index;
    this.locks = locks;
    this.executor = executor;
  }

  /** Manual trigger: starts a rebuild or throws LINK_REBUILD_RUNNING (409) when one is running. */
  public void trigger() {
    if (!start()) {
      throw new BusinessException(ErrorCode.LINK_REBUILD_RUNNING);
    }
  }

  /** Every day at 04:30 UTC. */
  @Scheduled(cron = "0 30 4 * * *", zone = "UTC")
  public void scheduled() {
    if (!start()) {
      LOG.info("Scheduled link index rebuild skipped: a rebuild is already running");
    }
  }

  /** Queues a rebuild unless one is running here or on another instance. */
  boolean start() {
    if (locks.isLocked(LOCK) || !running.compareAndSet(false, true)) {
      return false;
    }
    try {
      executor.execute(this::runLocked);
    } catch (RuntimeException e) {
      running.set(false);
      throw e;
    }
    return true;
  }

  private void runLocked() {
    try {
      // Non-positive lease: the watchdog keeps the lock for as long as the rebuild runs.
      if (!locks.run(LOCK, 0, -1, index::rebuildAll)) {
        LOG.info("Link index rebuild skipped: another instance holds the lock");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.error("Link index rebuild failed", e);
    } finally {
      running.set(false);
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }
}
