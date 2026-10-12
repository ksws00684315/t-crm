package com.tcrm.admin.security.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Image captcha required on every administrator login (design document §3.3).
 *
 * <ul>
 *   <li>The answer lives in Redis under {@code auth:captcha:{id}} for {@link #TTL}; ids are random
 *       UUIDs, so a caller can only verify captchas it was issued.
 *   <li>Single use: verification reads and deletes the key in one GETDEL, whether the answer is
 *       right or wrong, so one image allows exactly one guess.
 *   <li>Answers are compared case-insensitively.
 *   <li>Issuing is limited to {@link #ISSUE_LIMIT} per client IP per minute (ADR-008 addendum).
 * </ul>
 */
@Service
public class CaptchaService {
  static final Duration TTL = Duration.ofMinutes(3);
  static final int ISSUE_LIMIT = 30;
  static final long ISSUE_WINDOW_MS = 60_000;
  static final String KEY_PREFIX = "auth:captcha:";
  private static final DefaultRedisScript<Long> COUNT =
      new DefaultRedisScript<>(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
          return count
          """,
          Long.class);
  private final StringRedisTemplate redis;
  private final CaptchaImageGenerator generator;
  private final boolean enabled;

  public CaptchaService(
      StringRedisTemplate redis,
      CaptchaImageGenerator generator,
      @Value("${crm.auth.captcha.enabled:true}") boolean enabled) {
    this.redis = redis;
    this.generator = generator;
    this.enabled = enabled;
  }

  public record IssuedCaptcha(boolean enabled, String captchaId, String image, long expiresIn) {}

  public boolean enabled() {
    return enabled;
  }

  /** Issues a new captcha; when captchas are disabled returns {@code enabled = false} only. */
  public IssuedCaptcha issue(String clientIp) {
    if (!enabled) {
      return new IssuedCaptcha(false, null, null, 0);
    }
    Long issued =
        redis.execute(
            COUNT,
            List.of(KEY_PREFIX + "rate:" + digest(clientIp)),
            Long.toString(ISSUE_WINDOW_MS));
    if (issued != null && issued > ISSUE_LIMIT) {
      throw new BusinessException(ErrorCode.AUTH_CAPTCHA_RATE_LIMITED);
    }
    var captcha = generator.generate();
    String id = UUID.randomUUID().toString();
    redis.opsForValue().set(KEY_PREFIX + id, captcha.answer(), TTL);
    return new IssuedCaptcha(
        true,
        id,
        "data:image/png;base64," + Base64.getEncoder().encodeToString(captcha.png()),
        TTL.toSeconds());
  }

  /**
   * Consumes the captcha and throws AUTH_CAPTCHA_INVALID unless the answer matches. Does nothing
   * when captchas are disabled.
   */
  public void verify(String captchaId, String code) {
    if (!enabled) {
      return;
    }
    if (captchaId == null || captchaId.isBlank() || !isUuid(captchaId)) {
      throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
    }
    String answer = redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
    if (answer == null
        || code == null
        || !MessageDigest.isEqual(
            answer.getBytes(StandardCharsets.UTF_8),
            code.strip().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
    }
  }

  private static boolean isUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return false;
    }
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
