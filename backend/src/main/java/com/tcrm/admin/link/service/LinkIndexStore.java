package com.tcrm.admin.link.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis side of the link index (design §7.4): {@code HASH link:{kolCode}}, field = original product
 * code, and {@code HASH linkitem:{last two digits}} (task 11 §4.4), field = exclusive item ID; both
 * values are the same JSON from {@link LinkRules#indexValue}. Large writes are pipelined in chunks.
 */
@Component
public class LinkIndexStore {
  static final String KEY_PREFIX = "link:";

  /** Item index buckets; shares the {@code link:} prefix, e.g. {@code link:KOL0001}. */
  static final String ITEM_KEY_PREFIX = "linkitem:";

  static final int ITEM_BUCKETS = 100;

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
   * The next write ({@code link:*} or {@code linkitem:*}) throws this exception once, then writes
   * normally. Null clears a pending failure.
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
   * Sets the given exclusive-item fields of their {@code linkitem:{nn}} bucket; other fields of the
   * bucket are left untouched (task 11 §4.4).
   *
   * @param itemFields exclusive item ID to index value, all buckets merged
   */
  public void putItemFields(Map<String, String> itemFields) {
    checkWriteFailure();
    byBucket(itemFields.keySet())
        .forEach((bucket, fields) -> write(ITEM_KEY_PREFIX + bucket, pick(itemFields, fields)));
  }

  /**
   * Removes the exclusive-item fields from their {@code linkitem:{nn}} buckets.
   *
   * @param weidianItemIds exclusive item IDs whose fields no longer exist
   */
  public void removeItemFields(Collection<String> weidianItemIds) {
    checkWriteFailure();
    if (weidianItemIds == null || weidianItemIds.isEmpty()) {
      return;
    }
    byBucket(weidianItemIds)
        .forEach(
            (bucket, fields) ->
                redis.opsForHash().delete(ITEM_KEY_PREFIX + bucket, fields.toArray()));
  }

  /** Field names of one item bucket in HSCAN order; the rebuild sweep uses them all. */
  public List<String> listItemFields(String bucket) {
    List<String> fields = new ArrayList<>();
    try (var cursor =
        redis
            .opsForHash()
            .scan(ITEM_KEY_PREFIX + bucket, ScanOptions.scanOptions().count(1000).build())) {
      cursor.forEachRemaining(entry -> fields.add(String.valueOf(entry.getKey())));
    }
    return fields;
  }

  /** Every item bucket name, {@code "00"} to {@code "99"}. */
  public static List<String> itemBuckets() {
    List<String> buckets = new ArrayList<>(ITEM_BUCKETS);
    for (int i = 0; i < ITEM_BUCKETS; i++) {
      buckets.add(String.format(Locale.ROOT, "%02d", i));
    }
    return buckets;
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

  /** Groups exclusive item IDs by their bucket suffix, keeping encounter order. */
  private static Map<String, List<String>> byBucket(Collection<String> weidianItemIds) {
    Map<String, List<String>> byBucket = new LinkedHashMap<>();
    for (String itemId : weidianItemIds) {
      byBucket.computeIfAbsent(LinkRules.itemBucket(itemId), b -> new ArrayList<>()).add(itemId);
    }
    return byBucket;
  }

  private static Map<String, String> pick(Map<String, String> values, List<String> keys) {
    Map<String, String> picked = new LinkedHashMap<>(keys.size() * 2);
    for (String key : keys) {
      picked.put(key, values.get(key));
    }
    return picked;
  }
}
