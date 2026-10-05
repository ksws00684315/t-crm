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

/**
 * Login failure throttling (ADR-008). Failures are counted in a sliding 15-minute window on three
 * independent scopes, and reaching a limit locks that scope for 15 minutes:
 *
 * <ul>
 *   <li>username + client IP: 5 failures locks that user only for that IP, so one attacker cannot
 *       lock a victim out of their own sessions;
 *   <li>client IP: 20 failures against any usernames locks all logins from that IP;
 *   <li>username: 50 failures from any IPs locks the account everywhere, bounding distributed
 *       guessing at the cost of a deliberate, much more expensive lock-out.
 * </ul>
 */
@Component
public class LoginFailures {
  static final int PAIR_LIMIT = 5;
  static final int IP_LIMIT = 20;
  static final int ACCOUNT_LIMIT = 50;
  private static final long WINDOW_MS = 15 * 60 * 1000;
  private static final DefaultRedisScript<Long> FAIL =
      new DefaultRedisScript<>(
          """
          local now = tonumber(ARGV[1])
          local window = tonumber(ARGV[2])
          local limit = tonumber(ARGV[4])
          if redis.call('EXISTS', KEYS[2]) == 1 then return limit end
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
          redis.call('ZADD', KEYS[1], now, ARGV[3])
          redis.call('PEXPIRE', KEYS[1], window)
          local count = redis.call('ZCARD', KEYS[1])
          if count >= limit then
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

  /** Throws AUTH_ACCOUNT_LOCKED (429) while any scope of this attempt is locked. */
  public void check(String username, String ip) {
    for (Scope scope : scopes(username, ip)) {
      if (Boolean.TRUE.equals(redis.hasKey(scope.key() + ":lock"))) {
        throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
      }
    }
  }

  /**
   * Records a failed attempt on every scope; throws AUTH_ACCOUNT_LOCKED when one reaches its limit.
   */
  public void fail(String username, String ip) {
    boolean locked = false;
    for (Scope scope : scopes(username, ip)) {
      Long count =
          redis.execute(
              FAIL,
              List.of(scope.key(), scope.key() + ":lock"),
              Long.toString(System.currentTimeMillis()),
              Long.toString(WINDOW_MS),
              UUID.randomUUID().toString(),
              Integer.toString(scope.limit()));
      locked |= count != null && count >= scope.limit();
    }
    if (locked) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }
  }

  /**
   * Clears only the username + IP counter after a successful login. The IP and account counters
   * keep running so a valid account cannot be used to reset an ongoing guessing attack.
   */
  public void clear(String username, String ip) {
    redis.delete(scopes(username, ip).getFirst().key());
  }

  private record Scope(String key, int limit) {}

  private static List<Scope> scopes(String username, String ip) {
    String user = digest(username.toLowerCase(Locale.ROOT));
    String address = digest(ip);
    return List.of(
        new Scope("auth:fail:user-ip:" + user + ":" + address, PAIR_LIMIT),
        new Scope("auth:fail:ip:" + address, IP_LIMIT),
        new Scope("auth:fail:user:" + user, ACCOUNT_LIMIT));
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
