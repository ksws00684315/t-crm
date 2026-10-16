package com.tcrm.redirect.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ClickRedis {
  private final StringRedisTemplate redis;
  private final XAddOptions trim;

  public ClickRedis(
      StringRedisTemplate redis,
      @Value("${crm.redirect.click-stream-max-len:1000000}") long maxLength) {
    if (maxLength <= 0) {
      throw new IllegalArgumentException("Click stream max length must be positive");
    }
    this.redis = redis;
    this.trim = XAddOptions.maxlen(maxLength).approximateTrimming(true);
  }

  public String lookup(String kolCode, String productCode) {
    return (String) redis.opsForHash().get("link:" + kolCode, productCode);
  }

  /** Reads the copied-item index {@code linkitem:{nn}} (design §7.4, ADR-019). */
  public String lookupItem(String weidianItemId) {
    return (String) redis.opsForHash().get(itemKey(weidianItemId), weidianItemId);
  }

  /**
   * Bucket key of a copied Weidian item: the last two digits of the ID, left-padded with {@code 0}
   * (ID {@code 7} is in {@code linkitem:07}, ID {@code 7849640325} in {@code linkitem:25}).
   */
  static String itemKey(String weidianItemId) {
    String padded = weidianItemId.length() < 2 ? "0" + weidianItemId : weidianItemId;
    return "linkitem:" + padded.substring(padded.length() - 2);
  }

  public void append(Map<String, String> event) {
    redis.opsForStream().add(MapRecord.create("stream:click", event), trim);
  }

  public boolean healthy() {
    return Boolean.TRUE.equals(
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<Boolean>)
                connection -> "PONG".equals(connection.ping())));
  }
}
