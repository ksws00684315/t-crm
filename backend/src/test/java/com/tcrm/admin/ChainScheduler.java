package com.tcrm.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * Keeps {@code @Scheduled} jobs from running during a chain test. Boot skips its own {@code
 * taskScheduler} bean when this one is present. Tests call the job methods themselves.
 */
@TestConfiguration
public class ChainScheduler {
  @Bean(name = "taskScheduler")
  TaskScheduler taskScheduler() {
    return new IdleScheduler();
  }

  private static final class IdleScheduler implements TaskScheduler {
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      return IdleFuture.DONE;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      return IdleFuture.DONE;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) {
      return IdleFuture.DONE;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      return IdleFuture.DONE;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
      return IdleFuture.DONE;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      return IdleFuture.DONE;
    }
  }

  /** Already finished, so context shutdown does not wait for a job that never started. */
  private static final class IdleFuture implements ScheduledFuture<Object> {
    static final IdleFuture DONE = new IdleFuture();

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }
}
