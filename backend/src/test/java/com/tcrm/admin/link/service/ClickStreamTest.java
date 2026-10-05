package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ClickStreamTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final ClickStream stream = new ClickStream(redis);

  @BeforeEach
  void setup() {
    when(redis.opsForStream()).thenReturn(ops);
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void shouldCreateGroupFromFirstEntry() {
    stream.ensureGroup();
    verify(ops).createGroup("stream:click", ReadOffset.from("0"), "crm-admin");
  }

  @Test
  void shouldIgnoreBusyGroup_butRethrowOtherErrors() {
    when(ops.createGroup(any(), any(ReadOffset.class), any()))
        .thenThrow(
            new RedisSystemException(
                "x", new RuntimeException("BUSYGROUP Consumer Group name already exists")));
    stream.ensureGroup();

    when(ops.createGroup(any(), any(ReadOffset.class), any()))
        .thenThrow(new RedisSystemException("x", new RuntimeException("connection refused")));
    assertThatThrownBy(stream::ensureGroup).isInstanceOf(RedisSystemException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldReadPendingEntriesAsMessages() {
    MapRecord<String, Object, Object> record =
        MapRecord.create("stream:click", Map.<Object, Object>of("kolItemId", "5"))
            .withId(RecordId.of("1-2"));
    when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class)))
        .thenReturn(List.of(record));

    var messages = stream.readPending(500);

    assertThat(messages).containsExactly(new ClickMessage("1-2", Map.of("kolItemId", "5")));
    verify(ops)
        .read(
            eq(Consumer.from("crm-admin", "crm-admin")),
            any(StreamReadOptions.class),
            eq(StreamOffset.create("stream:click", ReadOffset.from("0"))));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldReadNewEntries_andHandleEmptyResults() {
    when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class)))
        .thenReturn(null);
    assertThat(stream.readNew(500, Duration.ofSeconds(2))).isEmpty();
    verify(ops)
        .read(
            any(Consumer.class),
            any(StreamReadOptions.class),
            eq(StreamOffset.create("stream:click", ReadOffset.lastConsumed())));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRecreateGroup_whenGroupMissing() {
    when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class)))
        .thenThrow(new RedisSystemException("x", new RuntimeException("NOGROUP No such key")));

    assertThat(stream.readPending(500)).isEmpty();
    verify(ops).createGroup("stream:click", ReadOffset.from("0"), "crm-admin");

    when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class)))
        .thenThrow(new RedisSystemException("x", new RuntimeException("timeout")));
    assertThatThrownBy(() -> stream.readPending(500)).isInstanceOf(RedisSystemException.class);
  }

  @Test
  void shouldAckOnlyWhenThereIsSomething() {
    stream.ack(List.of());
    verify(ops, never()).acknowledge(any(String.class), any(String.class), any(String[].class));
    stream.ack(List.of("1-0", "1-1"));
    verify(ops).acknowledge("stream:click", "crm-admin", "1-0", "1-1");
  }

  @Test
  void shouldReadLastClickTimes_skippingMissingAndMalformed() {
    assertThat(stream.lastClickTimes(List.of())).isEmpty();
    when(values.multiGet(List.of("a", "b", "c"))).thenReturn(Arrays.asList("5", null, "x"));
    assertThat(stream.lastClickTimes(List.of("a", "b", "c"))).isEqualTo(Map.of("a", 5L));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldPipelineLastClickTimes() {
    stream.saveLastClickTimes(Map.of());
    verify(redis, never()).executePipelined(any(RedisCallback.class));
    stream.saveLastClickTimes(Map.of("a", 5L));
    verify(redis, times(1)).executePipelined(any(RedisCallback.class));
  }
}
