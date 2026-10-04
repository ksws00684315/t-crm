package com.tcrm.redirect;

import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ClickRedis {
  private final StringRedisTemplate redis;

  public ClickRedis(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public String lookup(String kolCode, String productCode) {
    return (String) redis.opsForHash().get("link:" + kolCode, productCode);
  }

  public void append(Map<String, String> event) {
    redis.opsForStream().add(MapRecord.create("stream:click", event));
  }

  public boolean healthy() {
    return Boolean.TRUE.equals(
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<Boolean>)
                connection -> "PONG".equals(connection.ping())));
  }
}
