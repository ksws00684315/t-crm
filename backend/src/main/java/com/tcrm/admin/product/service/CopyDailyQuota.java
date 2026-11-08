package com.tcrm.admin.product.service;

import com.tcrm.admin.product.api.CopyDailyQuotaApi;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.MissingTenantException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Counts Weidian {@code vdian.item.add} calls per company per Beijing day in Redis (task 140). New
 * copy jobs are refused when their item count would exceed the remaining allowance; the copy itself
 * acquires one unit right before each add call, so concurrent jobs cannot overrun the limit.
 */
@Component
public class CopyDailyQuota implements CopyDailyQuotaApi {
  static final String LIMIT_KEY = "copy.daily-limit-per-company";
  static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Duration KEY_TTL = Duration.ofHours(48);
  private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>();

  static {
    ACQUIRE.setResultType(Long.class);
    ACQUIRE.setScriptText(
        """
        local used = tonumber(redis.call('GET', KEYS[1]) or '0')
        if used >= tonumber(ARGV[1]) then
          return -1
        end
        local count = redis.call('INCR', KEYS[1])
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
        return count
        """);
  }

  private final StringRedisTemplate redis;
  private final SystemConfigApi configs;
  private final Clock clock;

  @Autowired
  public CopyDailyQuota(StringRedisTemplate redis, SystemConfigApi configs) {
    this(redis, configs, Clock.systemUTC());
  }

  CopyDailyQuota(StringRedisTemplate redis, SystemConfigApi configs, Clock clock) {
    this.redis = redis;
    this.configs = configs;
    this.clock = clock;
  }

  /** Today's calls and limit for {@code tenantId}. */
  public Usage usage(long tenantId) {
    String value = redis.opsForValue().get(key(tenantId));
    long used = value == null ? 0 : Long.parseLong(value);
    return new Usage(used, configs.getInt(LIMIT_KEY));
  }

  /** Refuses a new job of {@code requested} items that would exceed today's remaining allowance. */
  public void requireRoom(long tenantId, int requested) {
    Usage usage = usage(tenantId);
    if (requested > usage.remaining()) {
      throw new BusinessException(
          ErrorCode.COPY_DAILY_QUOTA_EXCEEDED,
          ProductLinkMessages.dailyQuotaShort(
              usage.limit(), usage.used(), usage.remaining(), requested),
          null);
    }
  }

  @Override
  public boolean tryAcquire() {
    Long tenantId = TenantScope.companyId();
    if (tenantId == null) {
      throw new MissingTenantException("copy quota requires a company scope");
    }
    Long count =
        redis.execute(
            ACQUIRE,
            List.of(key(tenantId)),
            Integer.toString(configs.getInt(LIMIT_KEY)),
            Long.toString(KEY_TTL.toSeconds()));
    return count != null && count >= 0;
  }

  String key(long tenantId) {
    return "copy:item-add:daily:" + tenantId + ":" + LocalDate.now(clock.withZone(DAY_ZONE));
  }

  /** Calls counted today and the configured daily limit. */
  public record Usage(long used, int limit) {
    public long remaining() {
      return Math.max(0, limit - used);
    }
  }
}
