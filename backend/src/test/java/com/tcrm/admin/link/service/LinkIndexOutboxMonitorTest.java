package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.link.model.LinkIndexScope;
import com.tcrm.admin.shared.DistributedLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LinkIndexOutboxMonitorTest {
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
  private final LinkIndexOutboxMapper outbox = mock(LinkIndexOutboxMapper.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final LinkIndexOutboxMonitor monitor =
      new LinkIndexOutboxMonitor(outbox, locks, Clock.fixed(NOW, ZoneOffset.UTC));
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(LinkIndexOutboxMonitor.class);

  @BeforeEach
  void attachLogs() {
    logs.start();
    logger.addAppender(logs);
    when(outbox.listAttemptsAtLeast(LinkIndexOutboxMonitor.ATTEMPTS_ERROR)).thenReturn(List.of());
  }

  @AfterEach
  void detachLogs() {
    logger.detachAppender(logs);
  }

  @Test
  void shouldGradeWaitsStrictlyAboveTheThresholds() {
    assertThat(LinkIndexOutboxMonitor.waitLevel(Duration.ofMinutes(1)))
        .isEqualTo(LinkIndexOutboxMonitor.Level.OK);
    assertThat(LinkIndexOutboxMonitor.waitLevel(Duration.ofMinutes(1).plusMillis(1)))
        .isEqualTo(LinkIndexOutboxMonitor.Level.WARN);
    assertThat(LinkIndexOutboxMonitor.waitLevel(Duration.ofMinutes(10)))
        .isEqualTo(LinkIndexOutboxMonitor.Level.WARN);
    assertThat(LinkIndexOutboxMonitor.waitLevel(Duration.ofMinutes(10).plusMillis(1)))
        .isEqualTo(LinkIndexOutboxMonitor.Level.ERROR);
  }

  @Test
  void shouldNotLogAWait_whenTheOutboxIsEmpty() {
    monitor.check();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void shouldWarnThenError_asTheOldestRowAges() {
    when(outbox.oldestCreateTime()).thenReturn(NOW.minus(Duration.ofMinutes(2)));
    monitor.check();
    assertThat(levels()).containsExactly("WARN");

    logs.list.clear();
    when(outbox.oldestCreateTime()).thenReturn(NOW.minus(Duration.ofMinutes(10).plusMillis(1)));
    monitor.check();
    assertThat(levels()).containsExactly("ERROR");
  }

  @Test
  void shouldError_whenARowHasBeenRetriedFiveTimes() {
    LinkIndexOutboxDO row = new LinkIndexOutboxDO();
    row.setId(9L);
    row.setScope(LinkIndexScope.SHOP);
    row.setAttempts(5);
    when(outbox.listAttemptsAtLeast(5)).thenReturn(List.of(row));

    monitor.check();

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getLevel().toString()).isEqualTo("ERROR");
    assertThat(logs.list.getFirst().getFormattedMessage()).contains("SHOP").contains("9");
  }

  @Test
  void shouldCheckUnderItsOwnLock() throws InterruptedException {
    when(locks.run(eq(LinkIndexOutboxMonitor.LOCK), eq(0L), eq(50L), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(3, Runnable.class).run();
              return true;
            });

    monitor.scheduled();

    verify(locks).run(eq("link:index-outbox-monitor"), eq(0L), eq(50L), any(Runnable.class));
    verify(outbox).oldestCreateTime();
  }

  private List<String> levels() {
    return logs.list.stream().map(event -> event.getLevel().toString()).toList();
  }
}
