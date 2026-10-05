package com.tcrm.admin.link.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * Redis side of click consumption (task 06 §4.5): consumer group {@code crm-admin} on {@code
 * stream:click} and the {@code click:last:*} keys of the dup check. One fixed consumer name is used
 * because only the instance holding the consumer lock reads, so its pending list survives restarts.
 */
@Component
public class ClickStream {
  static final String STREAM = "stream:click";
  static final String GROUP = "crm-admin";
  static final String CONSUMER = "crm-admin";
  static final Duration LAST_CLICK_TTL = Duration.ofSeconds(60);
  private static final Logger LOG = LoggerFactory.getLogger(ClickStream.class);
  private final StringRedisTemplate redis;

  public ClickStream(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Creates the group (and the stream) from the first entry, so clicks written before the first
   * start are consumed too; an existing group is left as is.
   */
  public void ensureGroup() {
    try {
      redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
      LOG.info("Created consumer group {} on {}", GROUP, STREAM);
    } catch (DataAccessException e) {
      if (!hasMessage(e, "BUSYGROUP")) {
        throw e;
      }
    }
  }

  /** Entries delivered to this consumer but not acknowledged yet (the pending list). */
  public List<ClickMessage> readPending(int count) {
    return read(StreamReadOptions.empty().count(count), ReadOffset.from("0"));
  }

  /** New entries; blocks up to {@code block} when there are none. */
  public List<ClickMessage> readNew(int count, Duration block) {
    return read(StreamReadOptions.empty().count(count).block(block), ReadOffset.lastConsumed());
  }

  public void ack(List<String> ids) {
    if (!ids.isEmpty()) {
      redis.opsForStream().acknowledge(STREAM, GROUP, ids.toArray(String[]::new));
    }
  }

  /** Previous click times (epoch ms) by key; absent keys are missing from the result. */
  public Map<String, Long> lastClickTimes(List<String> keys) {
    Map<String, Long> times = new HashMap<>();
    if (keys.isEmpty()) {
      return times;
    }
    List<String> values = redis.opsForValue().multiGet(keys);
    for (int i = 0; values != null && i < keys.size(); i++) {
      String value = values.get(i);
      if (value != null) {
        try {
          times.put(keys.get(i), Long.parseLong(value));
        } catch (NumberFormatException e) {
          LOG.warn("Ignored malformed value of {}", keys.get(i));
        }
      }
    }
    return times;
  }

  /** Stores the latest click times with a 60 s TTL (pipelined). */
  public void saveLastClickTimes(Map<String, Long> times) {
    if (times.isEmpty()) {
      return;
    }
    redis.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              for (var entry : times.entrySet()) {
                connection
                    .stringCommands()
                    .set(
                        entry.getKey().getBytes(StandardCharsets.UTF_8),
                        Long.toString(entry.getValue()).getBytes(StandardCharsets.UTF_8),
                        Expiration.from(LAST_CLICK_TTL),
                        RedisStringCommands.SetOption.upsert());
              }
              return null;
            });
  }

  private List<ClickMessage> read(StreamReadOptions options, ReadOffset offset) {
    List<MapRecord<String, Object, Object>> records;
    try {
      records =
          redis
              .opsForStream()
              .read(Consumer.from(GROUP, CONSUMER), options, StreamOffset.create(STREAM, offset));
    } catch (DataAccessException e) {
      if (!hasMessage(e, "NOGROUP")) {
        throw e;
      }
      // The stream or group was deleted (e.g. Redis flushed): recreate and read on the next poll.
      LOG.warn("Consumer group {} on {} is missing; recreating it", GROUP, STREAM);
      ensureGroup();
      return List.of();
    }
    List<ClickMessage> messages = new ArrayList<>();
    if (records == null) {
      return messages;
    }
    for (var record : records) {
      Map<String, String> fields = new LinkedHashMap<>();
      if (record.getValue() != null) {
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
      }
      messages.add(new ClickMessage(record.getId().getValue(), fields));
    }
    return messages;
  }

  private static boolean hasMessage(Throwable e, String code) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(code)) {
        return true;
      }
    }
    return false;
  }
}
