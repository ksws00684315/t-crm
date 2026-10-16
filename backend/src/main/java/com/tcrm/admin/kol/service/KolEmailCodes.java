package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Registration email codes and the rate limits around them (task 12c §4.3). The Redis value is the
 * SHA-256 of the code plus the remaining attempts, never the code. Keys use the {@code
 * kol:register:} prefix, separate from login and password-reset counters.
 */
@Component
public class KolEmailCodes {
  static final int ATTEMPTS = 5;
  static final Duration CODE_TTL = Duration.ofMinutes(10);
  static final Duration COOLDOWN = Duration.ofSeconds(60);
  static final Duration WINDOW = Duration.ofHours(1);
  static final int SEND_IP_LIMIT = 20;
  static final int REGISTER_IP_LIMIT = 20;
  static final int REGISTER_EMAIL_LIMIT = 10;

  private static final String CODE_PREFIX = "kol:register:email-code:";
  private static final String COOLDOWN_PREFIX = "kol:register:email-cooldown:";
  private static final String SEND_IP_PREFIX = "kol:register:rate:send-ip:";
  private static final String REGISTER_IP_PREFIX = "kol:register:rate:reg-ip:";
  private static final String REGISTER_EMAIL_PREFIX = "kol:register:rate:reg-email:";

  /**
   * Atomic check: a match deletes the key; a mismatch spends one attempt and deletes the key at
   * zero. Returns 1 (accepted), 0 (wrong) or -1 (missing).
   */
  private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>();

  static {
    CONSUME.setResultType(Long.class);
    CONSUME.setScriptText(
        """
        local value = redis.call('GET', KEYS[1])
        if not value then
          return -1
        end
        local sep = string.find(value, ':', 1, true)
        if not sep then
          redis.call('DEL', KEYS[1])
          return -1
        end
        local hash = string.sub(value, 1, sep - 1)
        local attempts = tonumber(string.sub(value, sep + 1))
        if hash == ARGV[1] then
          redis.call('DEL', KEYS[1])
          return 1
        end
        attempts = (attempts or 1) - 1
        if attempts <= 0 then
          redis.call('DEL', KEYS[1])
        else
          redis.call('SET', KEYS[1], hash .. ':' .. attempts, 'KEEPTTL')
        end
        return 0
        """);
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  private final StringRedisTemplate redis;

  public KolEmailCodes(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void requireSendIp(String clientIp) {
    require(SEND_IP_PREFIX + digest(clientIp), SEND_IP_LIMIT);
  }

  public void requireRegisterIp(String clientIp) {
    require(REGISTER_IP_PREFIX + digest(clientIp), REGISTER_IP_LIMIT);
  }

  public void requireRegisterEmail(String email) {
    require(REGISTER_EMAIL_PREFIX + digest(email), REGISTER_EMAIL_LIMIT);
  }

  /**
   * Reserves the 60-second send slot for this company and email. A slot that is already taken is
   * {@code KOL_EMAIL_CODE_TOO_FREQUENT}.
   */
  public void reserveSend(long tenantId, String email) {
    Boolean reserved = redis.opsForValue().setIfAbsent(cooldownKey(tenantId, email), "1", COOLDOWN);
    if (!Boolean.TRUE.equals(reserved)) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_CODE_TOO_FREQUENT);
    }
  }

  /** Stores a new 6-digit code and returns it. The previous code for this email is replaced. */
  public String issue(long tenantId, String email) {
    String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    redis
        .opsForValue()
        .set(codeKey(tenantId, email), SecureTokens.hash(code) + ":" + ATTEMPTS, CODE_TTL);
    return code;
  }

  /**
   * Accepts the code once, or rejects it. Five wrong submissions delete the key; a later correct
   * submission is rejected too.
   */
  public void consume(long tenantId, String email, String submitted) {
    Long result =
        redis.execute(CONSUME, List.of(codeKey(tenantId, email)), SecureTokens.hash(submitted));
    if (result == null || result != 1L) {
      throw new BusinessException(ErrorCode.KOL_EMAIL_CODE_INVALID);
    }
  }

  private void require(String key, int limit) {
    if (increment(key) > limit) {
      throw new BusinessException(ErrorCode.KOL_REGISTER_TOO_FREQUENT);
    }
  }

  private long increment(String key) {
    Long value = redis.opsForValue().increment(key);
    if (value == null) {
      return Long.MAX_VALUE;
    }
    Long ttl = redis.getExpire(key);
    if (ttl == null || ttl < 0) {
      redis.expire(key, WINDOW);
    }
    return value;
  }

  private static String codeKey(long tenantId, String email) {
    return CODE_PREFIX + tenantId + ":" + digest(email);
  }

  private static String cooldownKey(long tenantId, String email) {
    return COOLDOWN_PREFIX + tenantId + ":" + digest(email);
  }

  private static String digest(String value) {
    return SecureTokens.hash(value == null ? "" : value);
  }
}
