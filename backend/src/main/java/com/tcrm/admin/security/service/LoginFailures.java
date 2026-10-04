package com.tcrm.admin.security.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class LoginFailures {
  private static final long WINDOW_MS = 15 * 60 * 1000;
  private static final DefaultRedisScript<Long> FAIL =
      new DefaultRedisScript<>(
          """
          local now = tonumber(ARGV[1])
          local window = tonumber(ARGV[2])
          if redis.call('EXISTS', KEYS[2]) == 1 then return 5 end
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
          redis.call('ZADD', KEYS[1], now, ARGV[3])
          redis.call('PEXPIRE', KEYS[1], window)
          local count = redis.call('ZCARD', KEYS[1])
          if count >= 5 then
            redis.call('SET', KEYS[2], '1', 'PX', window)
            redis.call('DEL', KEYS[1])
          end
          return count
          """,
          Long.class);
  private final StringRedisTemplate redis;

  public LoginFailures(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void check(String username) {
    if (Boolean.TRUE.equals(redis.hasKey(key(username) + ":lock"))) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }
  }

  public void fail(String username) {
    String key = key(username);
    Long count =
        redis.execute(
            FAIL,
            List.of(key, key + ":lock"),
            Long.toString(System.currentTimeMillis()),
            Long.toString(WINDOW_MS),
            UUID.randomUUID().toString());
    if (count != null && count >= 5) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }
  }

  public void clear(String username) {
    redis.delete(key(username));
  }

  private String key(String username) {
    try {
      return "auth:fail:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(username.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
