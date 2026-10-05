package com.tcrm.redirect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ClickRedisTest {
  @Test
  void shouldReadIndexByCodes_whenLookingUpLink() {
    var redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.get("link:KOL0001", "product")).thenReturn("1|https://example.com/item|1");
    assertThat(new ClickRedis(redis).lookup("KOL0001", "product"))
        .isEqualTo("1|https://example.com/item|1");
  }

  @Test
  void shouldAppendClickFieldsToStream_whenRecordingEvent() {
    var redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    when(redis.opsForStream()).thenReturn(streams);
    var fields = Map.of("kolCode", "KOL0001");
    new ClickRedis(redis).append(fields);
    var event = org.mockito.ArgumentCaptor.forClass(MapRecord.class);
    verify(streams).add(event.capture());
    assertThat(event.getValue().getStream()).isEqualTo("stream:click");
    assertThat(event.getValue().getValue()).isEqualTo(fields);
  }

  @Test
  void shouldReportRedisHealth_whenPingMatchesPong() {
    var redis = mock(StringRedisTemplate.class);
    var connection = mock(RedisConnection.class);
    when(redis.execute(any(RedisCallback.class)))
        .thenAnswer(i -> ((RedisCallback<Boolean>) i.getArgument(0)).doInRedis(connection));
    var clicks = new ClickRedis(redis);
    when(connection.ping()).thenReturn("PONG", "unexpected", null);
    assertThat(clicks.healthy()).isTrue();
    assertThat(clicks.healthy()).isFalse();
    assertThat(clicks.healthy()).isFalse();
  }

  @Test
  void shouldReportUnavailable_whenCallbackHasNoResult() {
    assertThat(new ClickRedis(mock(StringRedisTemplate.class)).healthy()).isFalse();
  }
}
