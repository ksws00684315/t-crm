package com.tcrm.admin.product.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window counters for the external product-link APIs (design §6.5): per IP on every call,
 * and per token on {@code convert} / {@code items/resolve}.
 */
@Component
public class ProductLinkRateLimiter {
  private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>();

  static {
    COUNT.setResultType(Long.class);
    COUNT.setScriptText(
        """
        local key = KEYS[1]
        local now = redis.call('TIME')
        local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
        local window = tonumber(ARGV[1])
        redis.call('ZREMRANGEBYSCORE', key, 0, ms - window)
        redis.call('ZADD', key, ms, ms .. '-' .. math.random(1, 1000000000))
        redis.call('PEXPIRE', key, window)
        return redis.call('ZCARD', key)
        """);
  }

  private final StringRedisTemplate redis;
  private final SystemConfigApi configs;
  private final int[] testLimits;

  @Autowired
  public ProductLinkRateLimiter(StringRedisTemplate redis, SystemConfigApi configs) {
    this.redis = redis;
    this.configs = configs;
    this.testLimits = null;
  }

  ProductLinkRateLimiter(
      StringRedisTemplate redis,
      int ipPerMinute,
      int convertTokenPerMinute,
      int resolveTokenPerMinute) {
    this.redis = redis;
    this.configs = null;
    this.testLimits = new int[] {ipPerMinute, convertTokenPerMinute, resolveTokenPerMinute};
  }

  public void checkIp(String clientIp) {
    check("product-links:ip:" + digest(clientIp), limit("product-links.ip-per-minute", 0));
  }

  public void checkConvertToken(long tokenId) {
    check(
        "product-links:token:convert:" + tokenId,
        limit("product-links.convert-token-per-minute", 1));
  }

  public void checkResolveToken(long tokenId) {
    check(
        "product-links:token:resolve:" + tokenId,
        limit("product-links.resolve-token-per-minute", 2));
  }

  private int limit(String key, int index) {
    return configs == null ? testLimits[index] : configs.getInt(key);
  }

  private void check(String key, int limit) {
    Long count = redis.execute(COUNT, List.of(key), Long.toString(60_000L));
    if (count != null && count > limit) {
      throw new BusinessException(
          ErrorCode.PRODUCT_LINK_RATE_LIMITED, ProductLinkMessages.RATE_LIMITED, null);
    }
  }

  private static String digest(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
