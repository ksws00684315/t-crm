package com.tcrm.admin.security.service;

import com.tcrm.admin.security.api.LoginFailureCounter;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
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
 *
 * <p>Administrators and KOLs each get their own instance with a distinct key prefix, so neither
 * namespace's counters affect the other (task 12a §4.4).
 */
@Component
@Primary
public class LoginFailures implements LoginFailureCounter {
  static final int PAIR_LIMIT = 5;
  static final int IP_LIMIT = 20;
  static final int ACCOUNT_LIMIT = 50;
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
  private final String keyPrefix;
  private final SystemConfigApi configs;

  /** Administrator namespace ({@code auth:fail:*}); the KOL bean uses the two-argument factory. */
  @Autowired
  public LoginFailures(StringRedisTemplate redis, SystemConfigApi configs) {
    this(redis, "auth", configs);
  }

  /** Shared core; {@code keyPrefix} separates the administrator and KOL namespaces. */
  public LoginFailures(StringRedisTemplate redis, String keyPrefix) {
    this(redis, keyPrefix, null);
  }

  public LoginFailures(StringRedisTemplate redis, String keyPrefix, SystemConfigApi configs) {
    this.redis = redis;
    this.keyPrefix = keyPrefix;
    this.configs = configs;
  }

  /** Throws AUTH_ACCOUNT_LOCKED (429) while any scope of this attempt is locked. */
  @Override
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
  @Override
  public void fail(String username, String ip) {
    boolean locked = false;
    for (Scope scope : scopes(username, ip)) {
      Long count =
          redis.execute(
              FAIL,
              List.of(scope.key(), scope.key() + ":lock"),
              Long.toString(System.currentTimeMillis()),
              Long.toString(windowMs()),
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

  @Override
  public Set<String> findLockedUsernames(Collection<String> usernames) {
    if (usernames == null || usernames.isEmpty()) {
      return Set.of();
    }
    List<String> ordered = new ArrayList<>();
    List<String> lockKeys = new ArrayList<>();
    for (String username : usernames) {
      if (username == null || username.isBlank()) {
        continue;
      }
      ordered.add(username);
      String user = digest(username.toLowerCase(Locale.ROOT));
      lockKeys.add(keyPrefix + ":fail:user:" + user + ":lock");
    }
    if (lockKeys.isEmpty()) {
      return Set.of();
    }
    List<Object> flags =
        redis.executePipelined(
            (RedisCallback<Object>)
                connection -> {
                  for (String lockKey : lockKeys) {
                    connection.keyCommands().exists(lockKey.getBytes(StandardCharsets.UTF_8));
                  }
                  return null;
                });
    Set<String> locked = new HashSet<>();
    for (int i = 0; i < ordered.size(); i++) {
      if (isPresent(flags.get(i))) {
        locked.add(ordered.get(i));
      }
    }
    return locked;
  }

  private static boolean isPresent(Object flag) {
    if (flag instanceof Boolean b) {
      return b;
    }
    if (flag instanceof Number n) {
      return n.longValue() > 0;
    }
    return false;
  }

  private record Scope(String key, int limit) {}

  private List<Scope> scopes(String username, String ip) {
    String user = digest(username.toLowerCase(Locale.ROOT));
    String address = digest(ip);
    return List.of(
        new Scope(
            keyPrefix + ":fail:user-ip:" + user + ":" + address,
            limit("login.fail.pair-limit", PAIR_LIMIT)),
        new Scope(keyPrefix + ":fail:ip:" + address, limit("login.fail.ip-limit", IP_LIMIT)),
        new Scope(
            keyPrefix + ":fail:user:" + user, limit("login.fail.account-limit", ACCOUNT_LIMIT)));
  }

  private int limit(String key, int fallback) {
    return configs == null ? fallback : configs.getInt(key);
  }

  private long windowMs() {
    return (configs == null ? 15 : configs.getInt("login.fail.window-minutes")) * 60_000L;
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
