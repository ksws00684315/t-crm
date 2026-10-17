package com.tcrm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TestWaitTest {
  @Test
  void shouldReturnImmediately_whenConditionAlreadyTrue() {
    long start = System.nanoTime();
    TestWait.until(Duration.ofSeconds(1), () -> true, "should not wait");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
  }

  @Test
  void shouldReturn_whenConditionBecomesTrue() {
    AtomicInteger n = new AtomicInteger();
    TestWait.until(
        Duration.ofSeconds(2),
        Duration.ofMillis(10),
        () -> n.incrementAndGet() >= 3,
        "counter never reached 3");
    assertThat(n.get()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void shouldFailWithMessage_whenTimedOut() {
    assertThatThrownBy(
            () ->
                TestWait.until(
                    Duration.ofMillis(80), Duration.ofMillis(10), () -> false, "never ready"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("never ready");
  }
}
