package com.tcrm.admin.link.service;

import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies {@code crm_link_index_outbox} to Redis (task 06b). Idle polls run every 2 seconds. While
 * rows are due, one instance drains them in batches of {@link #BATCH} under {@link #LOCK} until a
 * batch is empty, so a large import becomes readable without waiting out the idle interval between
 * batches. Each KOL is refreshed under {@link LinkIndexService#kolLock} after a fresh database
 * read. Failures stay in the table and back off; rows are never discarded.
 */
@Component
public class LinkIndexSyncJob {
  static final String LOCK = "link:index-sync";
  static final int BATCH = 200;
  static final int KOL_PAGE = 200;

  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexSyncJob.class);
  private static final int ERROR_MAX = 500;

  private final LinkIndexOutboxMapper outbox;
  private final LinkIndexService index;
  private final KolItemApi items;
  private final DistributedLocks locks;
  private final Clock clock;
  private final AtomicBoolean scheduled = new AtomicBoolean(true);

  @Autowired
  public LinkIndexSyncJob(
      LinkIndexOutboxMapper outbox,
      LinkIndexService index,
      KolItemApi items,
      DistributedLocks locks) {
    this(outbox, index, items, locks, Clock.systemUTC());
  }

  LinkIndexSyncJob(
      LinkIndexOutboxMapper outbox,
      LinkIndexService index,
      KolItemApi items,
      DistributedLocks locks,
      Clock clock) {
    this.outbox = outbox;
    this.index = index;
    this.items = items;
    this.locks = locks;
    this.clock = clock;
  }

  /**
   * When false, {@link #scheduled()} returns without taking the lock. Tests drive {@link #drain()}
   * themselves so a tick cannot write Redis on the request thread's behalf.
   */
  public void setScheduled(boolean enabled) {
    scheduled.set(enabled);
  }

  /** Idle poll. Skipped when another instance holds {@link #LOCK}. */
  @Scheduled(fixedDelay = 2_000)
  public void scheduled() throws InterruptedException {
    if (!scheduled.get()) {
      return;
    }
    locks.run(LOCK, 0, -1, this::drain);
  }

  /**
   * Processes every currently due row, {@link #BATCH} at a time, until a batch is empty or the
   * thread is interrupted. Callers that run beside {@link #scheduled()} must hold {@link #LOCK} or
   * be the only caller (tests).
   */
  public void drain() {
    TenantScope.runSystem("sync link index outbox across companies", this::drainInScope);
  }

  private void drainInScope() {
    while (!Thread.currentThread().isInterrupted()) {
      List<LinkIndexOutboxDO> due = outbox.listDue(clock.instant(), BATCH);
      if (due.isEmpty()) {
        return;
      }
      if (!apply(due)) {
        return;
      }
    }
  }

  /** Delay before the next try after {@code attempts} failures (the value already incremented). */
  static Duration backoff(int attempts) {
    return switch (attempts) {
      case 1 -> Duration.ofSeconds(10);
      case 2 -> Duration.ofSeconds(30);
      case 3 -> Duration.ofMinutes(1);
      case 4 -> Duration.ofMinutes(5);
      default -> Duration.ofMinutes(10);
    };
  }

  /** English one-line description stored in {@code last_error}, at most 500 characters. */
  static String errorText(Throwable error) {
    String detail = error.getMessage() == null ? "" : error.getMessage();
    String text =
        (error.getClass().getSimpleName() + ": " + detail).replaceAll("\\s+", " ").strip();
    return text.length() <= ERROR_MAX ? text : text.substring(0, ERROR_MAX);
  }

  /**
   * @return false when the thread was interrupted; those rows are left unchanged for the next run
   */
  private boolean apply(List<LinkIndexOutboxDO> due) {
    Map<Long, KolWork> works = new LinkedHashMap<>();
    Set<Long> succeeded = new LinkedHashSet<>();
    Map<Long, LinkIndexOutboxDO> pending = new LinkedHashMap<>();
    Map<Long, RuntimeException> failed = new LinkedHashMap<>();
    for (LinkIndexOutboxDO row : due) {
      try {
        if (expand(row, works) == 0) {
          succeeded.add(row.getId());
        } else {
          pending.put(row.getId(), row);
        }
      } catch (RuntimeException e) {
        failed.put(row.getId(), e);
      }
    }
    for (KolWork work : works.values()) {
      Outcome outcome = syncKol(work);
      if (outcome.stopped()) {
        finish(succeeded, failed, due);
        return false;
      }
      if (outcome.cause() != null) {
        for (Long id : work.rowIds) {
          pending.remove(id);
          failed.putIfAbsent(id, outcome.cause());
        }
      }
    }
    succeeded.addAll(pending.keySet());
    finish(succeeded, failed, due);
    return true;
  }

  private void finish(
      Set<Long> succeeded, Map<Long, RuntimeException> failed, List<LinkIndexOutboxDO> due) {
    if (!succeeded.isEmpty()) {
      outbox.deleteIds(succeeded);
    }
    Instant now = clock.instant();
    for (LinkIndexOutboxDO row : due) {
      RuntimeException error = failed.get(row.getId());
      if (error == null) {
        continue;
      }
      int attempts = row.getAttempts() + 1;
      row.setAttempts(attempts);
      row.setNextAttemptAt(now.plus(backoff(attempts)));
      row.setLastError(errorText(error));
      outbox.updateById(row);
      LOG.error("Link index sync failed for {} {}", row.getScope(), row.getId(), error);
    }
  }

  private Outcome syncKol(KolWork work) {
    try {
      boolean locked =
          locks.run(
              LinkIndexService.kolLock(work.kolId),
              LinkIndexService.KOL_LOCK_WAIT_SECONDS,
              -1,
              () -> {
                if (work.full) {
                  index.refreshKol(work.kolId);
                }
                for (Long productId : work.productIds) {
                  index.refreshMapping(work.kolId, productId);
                }
              });
      if (!locked) {
        return Outcome.failed(
            new IllegalStateException(
                "timed out waiting for " + LinkIndexService.kolLock(work.kolId)));
      }
      return Outcome.ok();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.halted();
    } catch (RuntimeException e) {
      return Outcome.failed(e);
    }
  }

  /**
   * @return how many KOL refreshes this row needs; zero means the row is already done
   */
  private int expand(LinkIndexOutboxDO row, Map<Long, KolWork> works) {
    // An unknown stored scope reads back as null (#106): skip the row instead of crashing the
    // sync loop; the lenient handler already logged the offending value.
    if (row.getScope() == null) {
      return 0;
    }
    return switch (row.getScope()) {
      case KOL -> {
        addFull(works, row.getScopeId(), row.getId());
        yield 1;
      }
      case MAPPING -> {
        addMapping(works, row.getScopeId(), row.getProductId(), row.getId());
        yield 1;
      }
      case PRODUCT ->
          expandPages(
              row,
              works,
              after -> items.pageKolIdsWithCurrentProduct(row.getScopeId(), after, KOL_PAGE));
      case SHOP ->
          expandPages(
              row,
              works,
              after -> items.pageKolIdsWithCurrentShop(row.getScopeId(), after, KOL_PAGE));
      default -> throw new IllegalStateException("Unexpected outbox scope " + row.getScope());
    };
  }

  private int expandPages(LinkIndexOutboxDO row, Map<Long, KolWork> works, Page page) {
    int count = 0;
    Long after = null;
    while (true) {
      List<Long> kolIds = page.fetch(after);
      if (kolIds.isEmpty()) {
        return count;
      }
      for (Long kolId : kolIds) {
        addFull(works, kolId, row.getId());
        count++;
      }
      after = kolIds.getLast();
      if (kolIds.size() < KOL_PAGE) {
        return count;
      }
    }
  }

  private static void addFull(Map<Long, KolWork> works, long kolId, long rowId) {
    KolWork work = works.computeIfAbsent(kolId, KolWork::new);
    work.full = true;
    work.rowIds.add(rowId);
  }

  private static void addMapping(Map<Long, KolWork> works, long kolId, long productId, long rowId) {
    KolWork work = works.computeIfAbsent(kolId, KolWork::new);
    work.productIds.add(productId);
    work.rowIds.add(rowId);
  }

  @FunctionalInterface
  private interface Page {
    List<Long> fetch(Long afterKolId);
  }

  private static final class KolWork {
    private final long kolId;
    private boolean full;
    private final Set<Long> productIds = new LinkedHashSet<>();
    private final Set<Long> rowIds = new LinkedHashSet<>();

    private KolWork(long kolId) {
      this.kolId = kolId;
    }
  }

  private record Outcome(boolean stopped, RuntimeException cause) {
    static Outcome ok() {
      return new Outcome(false, null);
    }

    static Outcome halted() {
      return new Outcome(true, null);
    }

    static Outcome failed(RuntimeException cause) {
      return new Outcome(false, cause);
    }
  }
}
