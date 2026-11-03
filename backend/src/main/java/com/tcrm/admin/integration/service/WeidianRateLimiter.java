package com.tcrm.admin.integration.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/** Shared per-appkey QPS budget for every Weidian API request. */
@Component
public class WeidianRateLimiter {
  private final RedissonClient redis;
  private final SystemConfigApi configs;

  public WeidianRateLimiter(RedissonClient redis, SystemConfigApi configs) {
    this.redis = redis;
    this.configs = configs;
  }

  public void acquire(String appKey) {
    int qps = configs.getInt("weidian.qps");
    RRateLimiter limiter = redis.getRateLimiter("weidian:api:qps:" + digest(appKey));
    // trySetRate keeps the existing window. Calling setRate on every acquire (the previous
    // behaviour) refilled permits and let five concurrent callers finish in ~10 ms (L-067).
    if (!limiter.trySetRate(RateType.OVERALL, qps, 1, RateIntervalUnit.SECONDS)) {
      RateLimiterConfig cfg = limiter.getConfig();
      if (cfg.getRate() != qps || cfg.getRateInterval() != 1_000L) {
        limiter.setRate(RateType.OVERALL, qps, 1, RateIntervalUnit.SECONDS);
      }
    }
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
