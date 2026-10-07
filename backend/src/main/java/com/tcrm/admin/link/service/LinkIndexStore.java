package com.tcrm.admin.link.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis side of the link index (design §7.4): {@code HASH link:{kolCode}}, field = original product
 * code, value = JSON from {@link LinkRules#indexValue}. Large writes are pipelined in chunks.
 */
@Component
public class LinkIndexStore {
  static final String KEY_PREFIX = "link:";

  /**
   * Rebuild staging keys; outside {@code link:*} so the redirect service and SCAN never see them.
   */
  static final String STAGING_PREFIX = "link-rebuild:";

  static final int CHUNK = 1000;
  private final StringRedisTemplate redis;
  private volatile Runnable beforeRename = () -> {};
  private volatile RuntimeException writeFailure;

  public LinkIndexStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Runs after a rebuild has written the staging key and before {@code RENAME} replaces the live
   * key. Tests use it to commit a newer mapping in that window. An empty replace deletes the live
   * key and does not call this hook.
   */
  public void setBeforeRename(Runnable hook) {
    this.beforeRename = hook == null ? () -> {} : hook;
  }

  /**
   * The next {@link #putAll}, {@link #remove} or {@link #replace} throws this exception once, then
   * writes normally. Null clears a pending failure.
   */
  public void failNextWrite(RuntimeException failure) {
    this.writeFailure = failure;
  }

  /** Sets the given fields of {@code link:{kolCode}}; other fields are left untouched. */
  public void putAll(String kolCode, Map<String, String> fields) {
    checkWriteFailure();
    if (!fields.isEmpty()) {
      write(KEY_PREFIX + kolCode, fields);
    }
  }

  /** Removes one field (the mapping no longer exists). */
  public void remove(String kolCode, String productCode) {
    checkWriteFailure();
    redis.opsForHash().delete(KEY_PREFIX + kolCode, productCode);
  }

  /**
   * Atomically replaces {@code link:{kolCode}} with exactly these fields: they are written to a
   * staging key which is then renamed over the live key. An empty map deletes the key.
   */
  public void replace(String kolCode, Map<String, String> fields) {
    checkWriteFailure();
    if (fields.isEmpty()) {
      redis.delete(KEY_PREFIX + kolCode);
      return;
    }
    String staging = STAGING_PREFIX + kolCode;
    redis.delete(staging);
    write(staging, fields);
    beforeRename.run();
    redis.rename(staging, KEY_PREFIX + kolCode);
  }

  private void checkWriteFailure() {
    RuntimeException failure = writeFailure;
    if (failure != null) {
      writeFailure = null;
      throw failure;
    }
  }

  /** KOL codes that currently have a {@code link:*} key (SCAN, not KEYS). */
  public Set<String> listIndexedKolCodes() {
    Set<String> codes = new HashSet<>();
    for (String key : scan(KEY_PREFIX + "*")) {
      codes.add(key.substring(KEY_PREFIX.length()));
    }
    return codes;
  }

  /** Deletes the whole index of a KOL. */
  public void delete(String kolCode) {
    redis.delete(KEY_PREFIX + kolCode);
  }

  /** Deletes staging keys left behind by an interrupted rebuild; returns how many were deleted. */
  public int deleteStagingKeys() {
    List<String> keys = scan(STAGING_PREFIX + "*");
    if (!keys.isEmpty()) {
      redis.delete(keys);
    }
    return keys.size();
  }

  private void write(String key, Map<String, String> fields) {
    byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
    List<Map<byte[], byte[]>> chunks = new ArrayList<>();
    Map<byte[], byte[]> chunk = new LinkedHashMap<>();
    for (var entry : fields.entrySet()) {
      chunk.put(
          entry.getKey().getBytes(StandardCharsets.UTF_8),
          entry.getValue().getBytes(StandardCharsets.UTF_8));
      if (chunk.size() == CHUNK) {
        chunks.add(chunk);
        chunk = new LinkedHashMap<>();
      }
    }
    if (!chunk.isEmpty()) {
      chunks.add(chunk);
    }
    redis.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              hashWrites(connection, rawKey, chunks);
              return null;
            });
  }

  private static void hashWrites(
      RedisConnection connection, byte[] key, List<Map<byte[], byte[]>> chunks) {
    for (Map<byte[], byte[]> chunk : chunks) {
      connection.hashCommands().hMSet(key, chunk);
    }
  }

  private List<String> scan(String pattern) {
    List<String> keys = new ArrayList<>();
    try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
      cursor.forEachRemaining(keys::add);
    }
    return keys;
  }
}
