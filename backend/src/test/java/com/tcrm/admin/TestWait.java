package com.tcrm.admin;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Polls a condition instead of sleeping a fixed time in integration tests. Prefer this over {@code
 * Thread.sleep} when waiting for an asynchronous result.
 */
public final class TestWait {
  private static final Duration DEFAULT_POLL = Duration.ofMillis(25);

  private TestWait() {}

  /** Waits until {@code condition} is true, or fails with {@code message} after {@code timeout}. */
  public static void until(Duration timeout, BooleanSupplier condition, String message) {
    until(timeout, DEFAULT_POLL, condition, message);
  }

  public static void until(
      Duration timeout, Duration poll, BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + timeout.toNanos();
    AssertionError last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (condition.getAsBoolean()) {
          return;
        }
        last = null;
      } catch (AssertionError e) {
        last = e;
      } catch (RuntimeException e) {
        last = new AssertionError(e);
      }
      try {
        Thread.sleep(Math.max(1L, poll.toMillis()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting: " + message, e);
      }
    }
    if (last != null) {
      throw new AssertionError(message + " (last failure: " + last.getMessage() + ")", last);
    }
    throw new AssertionError(message);
  }
}
