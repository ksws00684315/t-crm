package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi.DeleteResult;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import com.tcrm.admin.shared.TenantScope;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded pool that deletes Weidian exclusive items for cleanup details (task 29 / ADR-025). Same
 * pattern as {@link CopyJobWorker}: sequential within one KOL, resume on restart, stale PENDING
 * reclaim every few minutes. A row is claimed with a conditional {@code update_time} bump so two
 * workers never both call Weidian for the same detail.
 */
@Component
public class KolCleanupWorker implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(KolCleanupWorker.class);
  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_SUCCESS = "SUCCESS";
  static final String STATUS_FAILED = "FAILED";

  private final KolCleanupItemMapper items;
  private final WeidianItemCopyApi weidian;
  private final Duration claimLease;
  private final ExecutorService executor;

  @Autowired
  public KolCleanupWorker(
      KolCleanupItemMapper items,
      WeidianItemCopyApi weidian,
      @Value("${crm.kol-cleanup.worker-threads:2}") int threads,
      @Value("${crm.kol-cleanup.worker-queue:200}") int queue,
      @Value("${crm.kol-cleanup.claim-lease-seconds:300}") int claimLeaseSeconds) {
    this(
        items,
        weidian,
        Duration.ofSeconds(Math.max(30, claimLeaseSeconds)),
        new ThreadPoolExecutor(
            Math.max(1, threads),
            Math.max(1, threads),
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queue)),
            namedThreads(),
            new ThreadPoolExecutor.CallerRunsPolicy()));
  }

  /** Test constructor with an injected executor. */
  KolCleanupWorker(
      KolCleanupItemMapper items,
      WeidianItemCopyApi weidian,
      Duration claimLease,
      ExecutorService executor) {
    this.items = items;
    this.weidian = weidian;
    this.claimLease = claimLease;
    this.executor = executor;
  }

  public void submit(long kolId) {
    executor.execute(() -> processKol(kolId));
  }

  @Override
  public void run(ApplicationArguments args) {
    List<Long> pending =
        TenantScope.callSystem(
            "resume pending KOL cleanup deletes after restart", () -> items.listPendingKolIds(500));
    for (Long kolId : pending) {
      LOG.info("Resuming KOL cleanup deletes for kol {}", kolId);
      submit(kolId);
    }
  }

  /** Reclaims PENDING rows whose claim lease expired (worker crash mid-delete). */
  @Scheduled(fixedDelayString = "${crm.kol-cleanup.stale-scan-ms:300000}", initialDelay = 60_000)
  public void scanStalePending() {
    Instant staleBefore = Instant.now().minus(claimLease);
    List<Long> stale =
        TenantScope.callSystem(
            "scan stale pending KOL cleanup deletes",
            () -> items.listStalePendingKolIds(staleBefore, 500));
    for (Long kolId : stale) {
      LOG.info("Re-queueing stale KOL cleanup deletes for kol {}", kolId);
      submit(kolId);
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  void processKol(long kolId) {
    List<KolCleanupItemDO> pending =
        TenantScope.callSystem(
            "load pending cleanup items for worker",
            () ->
                items.selectList(
                    new LambdaQueryWrapper<KolCleanupItemDO>()
                        .eq(KolCleanupItemDO::getKolId, kolId)
                        .eq(KolCleanupItemDO::getStatus, STATUS_PENDING)
                        .orderByAsc(KolCleanupItemDO::getId)));
    if (pending.isEmpty()) {
      return;
    }
    Long tenantId = pending.getFirst().getTenantId();
    if (tenantId == null) {
      return;
    }
    TenantScope.runCompany(tenantId, () -> processInCompany(pending));
  }

  private void processInCompany(List<KolCleanupItemDO> pending) {
    for (KolCleanupItemDO row : pending) {
      try {
        processOne(row);
      } catch (RuntimeException e) {
        LOG.warn(
            "Cleanup item {} failed unexpectedly: {}", row.getId(), e.getClass().getSimpleName());
        markFailed(row.getId(), "删除失败，请稍后重试");
      }
    }
  }

  void processOne(KolCleanupItemDO row) {
    KolCleanupItemDO fresh = items.selectById(row.getId());
    if (fresh == null || !STATUS_PENDING.equals(fresh.getStatus())) {
      return;
    }
    if (!tryClaim(fresh)) {
      return;
    }
    DeleteResult result = weidian.deleteItem(fresh.getShopId(), fresh.getWeidianItemId());
    if (result.success()) {
      markSuccess(fresh.getId());
    } else {
      markFailed(fresh.getId(), truncate(result.failReason(), 500));
    }
  }

  /**
   * Claims a PENDING row by bumping {@code update_time}. Wins only when the row is still PENDING
   * and either matches the observed {@code update_time} (fresh claim) or the lease has expired
   * (resume after crash).
   */
  boolean tryClaim(KolCleanupItemDO row) {
    Instant now = Instant.now();
    Instant leaseExpired = now.minus(claimLease);
    int updated =
        items.update(
            null,
            new LambdaUpdateWrapper<KolCleanupItemDO>()
                .eq(KolCleanupItemDO::getId, row.getId())
                .eq(KolCleanupItemDO::getStatus, STATUS_PENDING)
                .and(
                    w ->
                        w.eq(KolCleanupItemDO::getUpdateTime, row.getUpdateTime())
                            .or()
                            .lt(KolCleanupItemDO::getUpdateTime, leaseExpired))
                .set(KolCleanupItemDO::getUpdateTime, now));
    return updated == 1;
  }

  private void markSuccess(long itemId) {
    Instant now = Instant.now();
    items.update(
        null,
        new LambdaUpdateWrapper<KolCleanupItemDO>()
            .eq(KolCleanupItemDO::getId, itemId)
            .eq(KolCleanupItemDO::getStatus, STATUS_PENDING)
            .set(KolCleanupItemDO::getStatus, STATUS_SUCCESS)
            .set(KolCleanupItemDO::getFailReason, null)
            .set(KolCleanupItemDO::getFinishTime, now)
            .set(KolCleanupItemDO::getUpdateTime, now));
  }

  private void markFailed(long itemId, String reason) {
    Instant now = Instant.now();
    items.update(
        null,
        new LambdaUpdateWrapper<KolCleanupItemDO>()
            .eq(KolCleanupItemDO::getId, itemId)
            .eq(KolCleanupItemDO::getStatus, STATUS_PENDING)
            .set(KolCleanupItemDO::getStatus, STATUS_FAILED)
            .set(KolCleanupItemDO::getFailReason, reason == null ? "删除失败" : reason)
            .set(KolCleanupItemDO::getFinishTime, now)
            .set(KolCleanupItemDO::getUpdateTime, now));
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static ThreadFactory namedThreads() {
    AtomicInteger sequence = new AtomicInteger();
    return work -> {
      Thread thread = new Thread(work, "kol-cleanup-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
