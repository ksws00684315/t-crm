package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared per-appkey QPS budget for every Weidian API request. */
@Component
public class WeidianRateLimiter {
  private final RedissonClient redis;
  private final int qps;

  public WeidianRateLimiter(RedissonClient redis, @Value("${crm.weidian.qps:5}") int qps) {
    if (qps < 1) {
      throw new IllegalStateException("crm.weidian.qps must be positive");
    }
    this.redis = redis;
    this.qps = qps;
  }

  public void acquire(String appKey) {
    RRateLimiter limiter = redis.getRateLimiter("weidian:api:qps:" + digest(appKey));
    limiter.trySetRate(RateType.OVERALL, qps, 1, RateIntervalUnit.SECONDS);
    if (!limiter.tryAcquire(1, 10, TimeUnit.SECONDS)) {
      throw new BusinessException(ErrorCode.WEIDIAN_RATE_LIMITED);
    }
  }

  private static String digest(String appKey) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(appKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }
}
