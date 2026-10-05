package com.tcrm.admin.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskQueueTest {
  @Test
  void shouldRejectWith429_whenThreadsAndQueueAreFull() throws Exception {
    TaskQueue queue = new TaskQueue();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(TaskQueue.THREADS);
    try {
      for (int i = 0; i < TaskQueue.THREADS; i++) {
        queue.submit(
            () -> {
              started.countDown();
              awaitQuietly(release);
            });
      }
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < TaskQueue.CAPACITY; i++) {
        queue.submit(() -> {});
      }
      assertThatThrownBy(() -> queue.submit(() -> {}))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).getErrorCode())
          .isEqualTo(ErrorCode.TASK_QUEUE_FULL);
    } finally {
      release.countDown();
      queue.shutdown();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
