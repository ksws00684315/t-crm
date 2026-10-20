package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** IP-based rate limits for public registration preview and registration endpoints. */
@Component
public class KolRegistrationRateLimiter {
  private static final Duration WINDOW = Duration.ofHours(1);
  private static final int REGISTER_IP_LIMIT = 20;
  private static final int PREVIEW_IP_LIMIT = 20;

  private final StringRedisTemplate redis;

  public KolRegistrationRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void requireRegisterIp(String clientIp) {
    require("kol:register:rate:reg-ip:" + SecureTokens.hash(clientIp), REGISTER_IP_LIMIT);
  }

  public void requirePreviewIp(String clientIp) {
    require("kol:register:rate:preview-ip:" + SecureTokens.hash(clientIp), PREVIEW_IP_LIMIT);
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
