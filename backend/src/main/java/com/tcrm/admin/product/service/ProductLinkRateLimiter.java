package com.tcrm.admin.product.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
  private final int ipPerMinute;
  private final int convertTokenPerMinute;
  private final int resolveTokenPerMinute;

  public ProductLinkRateLimiter(
      StringRedisTemplate redis,
      @Value("${crm.product-links.ip-rate-per-minute:120}") int ipPerMinute,
      @Value("${crm.product-links.convert-token-rate-per-minute:10}") int convertTokenPerMinute,
      @Value("${crm.product-links.resolve-token-rate-per-minute:120}") int resolveTokenPerMinute) {
    this.redis = redis;
    this.ipPerMinute = ipPerMinute;
    this.convertTokenPerMinute = convertTokenPerMinute;
    this.resolveTokenPerMinute = resolveTokenPerMinute;
  }

  public void checkIp(String clientIp) {
    check("product-links:ip:" + digest(clientIp), ipPerMinute);
  }

  public void checkConvertToken(long tokenId) {
    check("product-links:token:convert:" + tokenId, convertTokenPerMinute);
  }

  public void checkResolveToken(long tokenId) {
    check("product-links:token:resolve:" + tokenId, resolveTokenPerMinute);
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
