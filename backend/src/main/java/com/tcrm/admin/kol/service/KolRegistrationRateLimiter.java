package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** IP-based rate limits for public registration preview and registration endpoints. */
@Component
public class KolRegistrationRateLimiter {
  private static final Duration WINDOW = Duration.ofHours(1);
  private final StringRedisTemplate redis;
  private final SystemConfigApi configs;

  public KolRegistrationRateLimiter(StringRedisTemplate redis, SystemConfigApi configs) {
    this.redis = redis;
    this.configs = configs;
  }

  public void requireRegisterIp(String clientIp) {
    require(
        "kol:register:rate:reg-ip:" + SecureTokens.hash(clientIp),
        configs.getInt("kol.register.ip-per-hour"));
  }

  public void requirePreviewIp(String clientIp) {
    require(
        "kol:register:rate:preview-ip:" + SecureTokens.hash(clientIp),
        configs.getInt("kol.register-preview.ip-per-hour"));
  }

  private void require(String key, int limit) {
    Long value = redis.opsForValue().increment(key);
    if (value == null || value > limit) {
      throw new BusinessException(ErrorCode.KOL_REGISTER_TOO_FREQUENT);
    }
    Long ttl = redis.getExpire(key);
    if (ttl == null || ttl < 0) {
      redis.expire(key, WINDOW);
    }
  }
}
