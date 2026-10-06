package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

class LinkIndexStoreTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final LinkIndexStore store = new LinkIndexStore(redis);

  /**
   * Runs the captured pipeline callback against a mock connection and returns its hash commands.
   */
  @SuppressWarnings("unchecked")
  private RedisHashCommands runPipeline() {
    ArgumentCaptor<RedisCallback<Object>> callback = ArgumentCaptor.forClass(RedisCallback.class);
    verify(redis).executePipelined(callback.capture());
    RedisConnection connection = mock(RedisConnection.class);
    RedisHashCommands hashes = mock(RedisHashCommands.class);
    when(connection.hashCommands()).thenReturn(hashes);
    callback.getValue().doInRedis(connection);
    return hashes;
  }

  private static Map<String, String> fields(int count) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      fields.put("P" + i, "{}");
    }
    return fields;
  }

  @Test
  void shouldPipelineFieldsInChunks_whenWritingManyFields() {
    store.putAll("KOL0001", fields(2500));

    RedisHashCommands hashes = runPipeline();
    verify(hashes, times(3)).hMSet(any(byte[].class), anyMap());
  }

  @Test
  void shouldWriteToLiveKey_whenPuttingFields() {
    store.putAll("KOL0001", Map.of("P1", "{}"));

    RedisHashCommands hashes = runPipeline();
    verify(hashes).hMSet(any(byte[].class), anyMap());
    ArgumentCaptor<byte[]> key = ArgumentCaptor.forClass(byte[].class);
    verify(hashes).hMSet(key.capture(), anyMap());
    assertThat(new String(key.getValue(), StandardCharsets.UTF_8)).isEqualTo("link:KOL0001");
  }

  @Test
  void shouldSkipRedis_whenNoFieldsToPut() {
    store.putAll("KOL0001", Map.of());
    verify(redis, never()).executePipelined(any(RedisCallback.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldDeleteSingleField_whenRemoving() {
    HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    store.remove("KOL0001", "P1");
    verify(hash).delete("link:KOL0001", "P1");
  }

  @Test
  void shouldStageThenRename_whenReplacingKey() {
    store.replace("KOL0001", Map.of("P1", "{}"));

    var order = inOrder(redis);
    order.verify(redis).delete("link-rebuild:KOL0001");
    order.verify(redis).executePipelined(any(RedisCallback.class));
    order.verify(redis).rename("link-rebuild:KOL0001", "link:KOL0001");
  }

  @Test
  void shouldDeleteLiveKey_whenReplacingWithNoFields() {
    store.replace("KOL0001", Map.of());
    verify(redis).delete("link:KOL0001");
    verify(redis, never()).rename(any(), any());
  }

  @SuppressWarnings("unchecked")
  private void scanReturns(String... keys) {
    Cursor<String> cursor = mock(Cursor.class);
    var values = List.of(keys).iterator();
    when(cursor.hasNext()).thenAnswer(inv -> values.hasNext());
    when(cursor.next()).thenAnswer(inv -> values.next());
    doCallRealMethod().when(cursor).forEachRemaining(any());
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
  }

  @Test
  void shouldListKolCodesFromScannedKeys() {
    scanReturns("link:KOL0001", "link:KOL0002");
    assertThat(store.listIndexedKolCodes()).containsExactlyInAnyOrder("KOL0001", "KOL0002");
  }

  @Test
  void shouldDeleteLeftoverStagingKeys() {
    scanReturns("link-rebuild:KOL0001");
    assertThat(store.deleteStagingKeys()).isEqualTo(1);
    verify(redis).delete(List.of("link-rebuild:KOL0001"));
  }

  @Test
  void shouldNotDelete_whenNoStagingKeys() {
    scanReturns();
    assertThat(store.deleteStagingKeys()).isZero();
    verify(redis, never()).delete(any(java.util.Collection.class));
  }

  @Test
  void shouldDeleteWholeKey_whenDeletingKolIndex() {
    store.delete("KOL0009");
    verify(redis).delete("link:KOL0009");
  }
}
