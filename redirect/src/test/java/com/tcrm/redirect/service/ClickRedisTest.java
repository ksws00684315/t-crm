package com.tcrm.redirect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
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
    when(hashes.get("link:KOL0001", "product"))
        .thenReturn(
            "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}");
    assertThat(new ClickRedis(redis, 1000).lookup("KOL0001", "product"))
        .isEqualTo("{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}");
  }

  @Test
  void shouldBucketByLastTwoDigits_whenBuildingItemKey() {
    assertThat(ClickRedis.itemKey("7")).isEqualTo("linkitem:07");
    assertThat(ClickRedis.itemKey("0")).isEqualTo("linkitem:00");
    assertThat(ClickRedis.itemKey("10")).isEqualTo("linkitem:10");
    assertThat(ClickRedis.itemKey("05")).isEqualTo("linkitem:05");
    assertThat(ClickRedis.itemKey("7849640325")).isEqualTo("linkitem:25");
    assertThat(ClickRedis.itemKey("12345678901234567800")).isEqualTo("linkitem:00");
  }

  @Test
  void shouldReadItemIndexByBucketAndId_whenLookingUpItem() {
    var redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.get("linkitem:25", "7849640325"))
        .thenReturn(
            "{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}");
    assertThat(new ClickRedis(redis, 1000).lookupItem("7849640325"))
        .isEqualTo("{\"kolItemId\":\"1\",\"targetUrl\":\"https://example.com/item\",\"status\":1}");
  }

  @Test
  void shouldAppendClickFieldsToStream_whenRecordingEvent() {
    var redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    when(redis.opsForStream()).thenReturn(streams);
    var fields = Map.of("kolCode", "KOL0001");
    new ClickRedis(redis, 1000).append(fields);
    var event = org.mockito.ArgumentCaptor.forClass(MapRecord.class);
    var options = org.mockito.ArgumentCaptor.forClass(XAddOptions.class);
    verify(streams).add(event.capture(), options.capture());
    assertThat(event.getValue().getStream()).isEqualTo("stream:click");
    assertThat(event.getValue().getValue()).isEqualTo(fields);
  }

  @Test
  void shouldTrimStreamApproximately_whenAppendingClick() {
    var redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    when(redis.opsForStream()).thenReturn(streams);
    new ClickRedis(redis, 5000).append(Map.of("kolCode", "KOL0001"));
    var options = org.mockito.ArgumentCaptor.forClass(XAddOptions.class);
    verify(streams).add(any(MapRecord.class), options.capture());
    assertThat(options.getValue().getMaxlen()).isEqualTo(5000L);
    assertThat(options.getValue().isApproximateTrimming()).isTrue();
  }

  @Test
  void shouldRejectStartup_whenStreamLimitIsNotPositive() {
    var redis = mock(StringRedisTemplate.class);
    assertThatThrownBy(() -> new ClickRedis(redis, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClickRedis(redis, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReportRedisHealth_whenPingMatchesPong() {
    var redis = mock(StringRedisTemplate.class);
    var connection = mock(RedisConnection.class);
    when(redis.execute(any(RedisCallback.class)))
        .thenAnswer(i -> ((RedisCallback<Boolean>) i.getArgument(0)).doInRedis(connection));
    var clicks = new ClickRedis(redis, 1000);
    when(connection.ping()).thenReturn("PONG", "unexpected", null);
    assertThat(clicks.healthy()).isTrue();
    assertThat(clicks.healthy()).isFalse();
    assertThat(clicks.healthy()).isFalse();
  }

  @Test
  void shouldReportUnavailable_whenCallbackHasNoResult() {
    assertThat(new ClickRedis(mock(StringRedisTemplate.class), 1000).healthy()).isFalse();
  }
}
