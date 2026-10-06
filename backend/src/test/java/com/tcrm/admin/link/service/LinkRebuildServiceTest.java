package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LinkRebuildServiceTest {
  private final LinkIndexService index = mock(LinkIndexService.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final QueuedExecutor executor = new QueuedExecutor();
  private final LinkRebuildService service = new LinkRebuildService(index, locks, executor);

  /** Holds submitted work until {@link #drain()} so tests control when the rebuild runs. */
  static final class QueuedExecutor extends AbstractExecutorService {
    private final List<Runnable> queued = new ArrayList<>();
    private boolean shutdown;

    @Override
    public void execute(Runnable command) {
      queued.add(command);
    }

    void drain() {
      List<Runnable> work = new ArrayList<>(queued);
      queued.clear();
      work.forEach(Runnable::run);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  @Test
  void shouldRunRebuildUnderWatchdogLock_whenTriggered() throws Exception {
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(3)).run();
              return true;
            })
        .when(locks)
        .run(eq(LinkRebuildService.LOCK), eq(0L), eq(-1L), any());

    service.trigger();
    executor.drain();

    verify(index).rebuildAll();
  }

  @Test
  void shouldReturn409_whenRebuildAlreadyQueuedOnThisInstance() {
    service.trigger();
    assertThatThrownBy(service::trigger)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.LINK_REBUILD_RUNNING.message());
  }

  @Test
  void shouldReturn409_whenAnotherInstanceHoldsTheLock() {
    when(locks.isLocked(LinkRebuildService.LOCK)).thenReturn(true);
    assertThatThrownBy(service::trigger)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.LINK_REBUILD_RUNNING.message());
  }

  @Test
  void shouldAllowNextRebuild_whenPreviousOneFailed() throws Exception {
    when(locks.run(any(), anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("x"));
    service.trigger();
    executor.drain();
    assertThat(service.start()).isTrue();
  }

  @Test
  void shouldAllowNextRebuild_whenLockTakenByAnotherInstanceMeanwhile() throws Exception {
    when(locks.run(any(), anyLong(), anyLong(), any())).thenReturn(false);
    service.trigger();
    executor.drain();
    verify(index, never()).rebuildAll();
    assertThat(service.start()).isTrue();
  }

  @Test
  void shouldSkipQuietly_whenScheduledWhileRunning() {
    service.trigger();
    service.scheduled();
    assertThat(executor.queued).hasSize(1);
  }

  @Test
  void shouldRestoreInterruptFlag_whenInterruptedWhileWaitingForLock() throws Exception {
    when(locks.run(any(), anyLong(), anyLong(), any())).thenThrow(new InterruptedException());
    service.trigger();
    executor.drain();
    assertThat(Thread.interrupted()).isTrue();
    assertThat(service.start()).isTrue();
  }

  @Test
  void shouldStopExecutor_whenShuttingDown() {
    service.shutdown();
    assertThat(executor.isShutdown()).isTrue();
  }
}
