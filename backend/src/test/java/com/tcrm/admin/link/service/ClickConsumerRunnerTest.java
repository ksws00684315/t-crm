package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.DistributedLocks;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClickConsumerRunnerTest {
  private final ClickConsumer consumer = mock(ClickConsumer.class);
  private final ClickStream stream = mock(ClickStream.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);

  private ClickConsumerRunner runner(boolean enabled) {
    return new ClickConsumerRunner(consumer, stream, locks, enabled, Duration.ofMillis(20));
  }

  private void lockAlwaysGranted() throws InterruptedException {
    when(locks.run(eq(ClickConsumerRunner.LOCK), eq(0L), eq(-1L), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(3).run();
              return true;
            });
  }

  @Test
  void shouldConsumeUnderLock_untilStopped() throws Exception {
    lockAlwaysGranted();
    var runner = runner(true);

    runner.start();
    assertThat(runner.isRunning()).isTrue();
    verify(stream, timeout(2_000)).ensureGroup();
    verify(consumer, timeout(2_000).atLeast(2)).pollOnce();
    runner.stop();

    assertThat(runner.isRunning()).isFalse();
  }

  @Test
  void shouldRetry_whenLockHeldElsewhere() throws Exception {
    when(locks.run(eq(ClickConsumerRunner.LOCK), anyLong(), anyLong(), any())).thenReturn(false);
    var runner = runner(true);

    runner.start();
    verify(locks, timeout(2_000).atLeast(2))
        .run(eq(ClickConsumerRunner.LOCK), anyLong(), anyLong(), any());
    runner.stop();

    verifyNoInteractions(consumer);
  }

  @Test
  void shouldRetry_whenConsumingFails() throws Exception {
    lockAlwaysGranted();
    var calls = new AtomicInteger();
    when(consumer.pollOnce())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("db down");
              }
              return 0;
            });
    var runner = runner(true);

    runner.start();
    verify(stream, timeout(2_000).atLeast(2)).ensureGroup();
    runner.stop();
  }

  @Test
  void shouldNotStart_whenDisabled() {
    var runner = runner(false);
    runner.start();
    assertThat(runner.isRunning()).isFalse();
    runner.stop();
    verifyNoInteractions(locks);
  }

  @Test
  void shouldStopLoop_whenInterruptedWhileWaitingForLock() throws Exception {
    when(locks.run(any(), anyLong(), anyLong(), any())).thenThrow(new InterruptedException());
    var runner = runner(true);
    runner.start();
    verify(locks, timeout(2_000)).run(any(), anyLong(), anyLong(), any());
    runner.stop();
    verify(locks, atLeast(1)).run(any(), anyLong(), anyLong(), any());
  }
}
