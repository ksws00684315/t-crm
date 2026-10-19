package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi.DeleteResult;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KolCleanupWorkerTest {
  private final KolCleanupItemMapper items = mock(KolCleanupItemMapper.class);
  private final WeidianItemCopyApi weidian = mock(WeidianItemCopyApi.class);
  private KolCleanupWorker worker;

  @BeforeAll
  static void initMybatisPlus() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, KolCleanupItemDO.class);
  }

  @BeforeEach
  void setUp() {
    worker = new KolCleanupWorker(items, weidian, Duration.ofMinutes(5), new DirectExecutor());
  }

  @Test
  void shouldDeleteOnce_whenClaimWins() {
    KolCleanupItemDO row = pending(10L, 1L, Instant.parse("2026-10-19T00:00:00Z"));
    when(items.selectList(any())).thenReturn(List.of(row));
    when(items.selectById(10L)).thenReturn(row);
    when(items.update(eq(null), any())).thenReturn(1);
    when(weidian.deleteItem(2L, "w1")).thenReturn(new DeleteResult(true, null));

    worker.processKol(1L);

    verify(weidian, times(1)).deleteItem(2L, "w1");
    verify(items, times(2)).update(eq(null), any());
  }

  @Test
  void shouldSkipWeidian_whenClaimLoses() {
    KolCleanupItemDO row = pending(10L, 1L, Instant.parse("2026-10-19T00:00:00Z"));
    when(items.selectList(any())).thenReturn(List.of(row));
    when(items.selectById(10L)).thenReturn(row);
    when(items.update(eq(null), any())).thenReturn(0);

    worker.processKol(1L);

    verify(weidian, never()).deleteItem(anyLong(), any());
  }

  @Test
  void shouldCallWeidianOnlyOnce_whenTwoThreadsRaceSameRow() throws Exception {
    KolCleanupItemDO row = pending(10L, 1L, Instant.parse("2026-10-19T00:00:00Z"));
    AtomicBoolean claimed = new AtomicBoolean();
    KolCleanupWorker racing =
        new KolCleanupWorker(items, weidian, Duration.ofMinutes(5), new DirectExecutor()) {
          @Override
          boolean tryClaim(KolCleanupItemDO candidate) {
            return claimed.compareAndSet(false, true);
          }
        };
    when(items.selectById(10L)).thenReturn(row);
    when(items.update(eq(null), any())).thenReturn(1);
    when(weidian.deleteItem(2L, "w1")).thenReturn(new DeleteResult(true, null));

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    Runnable race =
        () -> {
          try {
            start.await(2, TimeUnit.SECONDS);
            racing.processOne(row);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        };
    Thread a = new Thread(race);
    Thread b = new Thread(race);
    a.start();
    b.start();
    start.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    verify(weidian, times(1)).deleteItem(2L, "w1");
  }

  @Test
  void shouldResumePendingKols_onStartup() {
    when(items.listPendingKolIds(500)).thenReturn(List.of(7L, 8L));
    AtomicInteger submitted = new AtomicInteger();
    KolCleanupWorker counting =
        new KolCleanupWorker(
            items,
            weidian,
            Duration.ofMinutes(5),
            new AbstractExecutorService() {
              @Override
              public void shutdown() {}

              @Override
              public List<Runnable> shutdownNow() {
                return List.of();
              }

              @Override
              public boolean isShutdown() {
                return false;
              }

              @Override
              public boolean isTerminated() {
                return false;
              }

              @Override
              public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
              }

              @Override
              public void execute(Runnable command) {
                submitted.incrementAndGet();
              }
            });
    counting.run(null);
    assertThat(submitted.get()).isEqualTo(2);
  }

  @Test
  void tryClaim_shouldWinOnlyOnce_forSameUpdateTime() {
    KolCleanupItemDO row = pending(10L, 1L, Instant.parse("2026-10-19T00:00:00Z"));
    when(items.update(eq(null), any())).thenReturn(1, 0);
    assertThat(worker.tryClaim(row)).isTrue();
    assertThat(worker.tryClaim(row)).isFalse();
  }

  private static KolCleanupItemDO pending(long id, long kolId, Instant updateTime) {
    KolCleanupItemDO row = new KolCleanupItemDO();
    row.setId(id);
    row.setKolId(kolId);
    row.setTenantId(1L);
    row.setShopId(2L);
    row.setWeidianItemId("w1");
    row.setStatus("PENDING");
    row.setUpdateTime(updateTime);
    return row;
  }

  private static final class DirectExecutor extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
