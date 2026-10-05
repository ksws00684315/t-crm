package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.link.service.ClickStreamMonitor.Level;
import com.tcrm.admin.link.service.ClickStreamMonitor.StreamState;
import com.tcrm.admin.shared.DistributedLocks;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamInfo;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class ClickStreamMonitorTest {
  private final RedissonClient redisson = mock(RedissonClient.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final ClickStreamMonitor monitor =
      new ClickStreamMonitor(redisson, redis, locks, 1_000_000);

  @Test
  void shouldUseLag_whenKnown() {
    assertThat(ClickStreamMonitor.backlog(new StreamState(900, "9-0", 120L, 30L, "5-0")))
        .isEqualTo(120);
    assertThat(ClickStreamMonitor.backlog(new StreamState(900, "9-0", 0L, 0L, "9-0"))).isZero();
  }

  @Test
  void shouldEstimate_whenLagUnknownOrGroupMissing() {
    // Redis returns nil lag (decoded as 0) although the group is behind: use max(XLEN, pending).
    assertThat(ClickStreamMonitor.backlog(new StreamState(900, "9-0", 0L, 30L, "5-0")))
        .isEqualTo(900);
    assertThat(ClickStreamMonitor.backlog(new StreamState(10, "9-0", 0L, 30L, "5-0")))
        .isEqualTo(30);
    assertThat(ClickStreamMonitor.backlog(new StreamState(10, "9-0", 0L, null, null)))
        .isEqualTo(10);
    assertThat(ClickStreamMonitor.backlog(new StreamState(700, "9-0", null, null, null)))
        .isEqualTo(700);
  }

  @Test
  void shouldGradeBacklogAgainstMaxLength() {
    long max = 1_000_000;
    double w = ClickStreamMonitor.BACKLOG_WARN;
    double e = ClickStreamMonitor.BACKLOG_ERROR;
    assertThat(ClickStreamMonitor.level(500_000, max, w, e)).isEqualTo(Level.OK);
    assertThat(ClickStreamMonitor.level(500_001, max, w, e)).isEqualTo(Level.WARN);
    assertThat(ClickStreamMonitor.level(800_000, max, w, e)).isEqualTo(Level.WARN);
    assertThat(ClickStreamMonitor.level(800_001, max, w, e)).isEqualTo(Level.ERROR);
  }

  @Test
  void shouldGradeMemory_andSkipWithoutLimit() {
    long max = 1_000;
    double w = ClickStreamMonitor.MEMORY_WARN;
    double e = ClickStreamMonitor.MEMORY_ERROR;
    assertThat(ClickStreamMonitor.level(700, max, w, e)).isEqualTo(Level.OK);
    assertThat(ClickStreamMonitor.level(701, max, w, e)).isEqualTo(Level.WARN);
    assertThat(ClickStreamMonitor.level(850, max, w, e)).isEqualTo(Level.WARN);
    assertThat(ClickStreamMonitor.level(851, max, w, e)).isEqualTo(Level.ERROR);
    assertThat(ClickStreamMonitor.level(5_000, 0, w, e)).isEqualTo(Level.OK);
  }

  @Test
  void shouldRejectNonPositiveMaxLength() {
    assertThatThrownBy(() -> new ClickStreamMonitor(redisson, redis, locks, 0))
        .hasMessageContaining("TCRM_CLICK_STREAM_MAXLEN");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldReadStreamGroupAndMemory_whenChecking() {
    RStream<Object, Object> rstream = mock(RStream.class);
    when(redisson.getStream("stream:click", StringCodec.INSTANCE)).thenReturn(rstream);
    when(rstream.isExists()).thenReturn(true);
    StreamInfo<Object, Object> info = new StreamInfo<>();
    info.setLength(900_000);
    info.setLastGeneratedId(new StreamMessageId(9, 0));
    when(rstream.getInfo()).thenReturn(info);
    when(rstream.listGroups())
        .thenReturn(
            List.of(
                new StreamGroup("other", 1, 0, new StreamMessageId(1, 0), 0, 5),
                new StreamGroup("crm-admin", 1, 2, new StreamMessageId(5, 0), 10, 850_000)));
    Properties memory = new Properties();
    memory.setProperty("used_memory", "900");
    memory.setProperty("maxmemory", "1000");
    when(redis.execute(any(RedisCallback.class))).thenReturn(memory);

    monitor.check();

    verify(rstream).listGroups();
    verify(redis).execute(any(RedisCallback.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldSurviveFailures_andMissingStream() {
    RStream<Object, Object> rstream = mock(RStream.class);
    when(redisson.getStream("stream:click", StringCodec.INSTANCE)).thenReturn(rstream);
    when(rstream.isExists()).thenReturn(false);
    when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("down"));
    monitor.check();

    when(rstream.isExists()).thenThrow(new IllegalStateException("down"));
    Properties memory = new Properties();
    memory.setProperty("used_memory", "900");
    memory.setProperty("maxmemory", "0");
    when(redis.execute(any(RedisCallback.class))).thenReturn(memory);
    monitor.check();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldCheckWithoutGroup() {
    RStream<Object, Object> rstream = mock(RStream.class, RETURNS_DEEP_STUBS);
    when(redisson.getStream("stream:click", StringCodec.INSTANCE)).thenReturn(rstream);
    when(rstream.isExists()).thenReturn(true);
    StreamInfo<Object, Object> info = new StreamInfo<>();
    info.setLength(10);
    when(rstream.getInfo()).thenReturn(info);
    when(rstream.listGroups()).thenReturn(List.of());
    monitor.check();
    verify(rstream).listGroups();
  }

  @Test
  void shouldRunUnderLock() throws Exception {
    monitor.scheduled();
    verify(locks).run(eq(ClickStreamMonitor.LOCK), eq(0L), anyLong(), any());
  }
}
