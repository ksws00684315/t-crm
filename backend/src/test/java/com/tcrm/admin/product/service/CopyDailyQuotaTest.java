package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.MissingTenantException;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class CopyDailyQuotaTest {
  // 2026-11-08 16:30 UTC is already 2026-11-09 in Beijing.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-08T16:30:00Z"), ZoneOffset.UTC);
  private static final String KEY = "copy:item-add:daily:7:2026-11-09";
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private CopyDailyQuota quota;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    when(configs.getInt("copy.daily-limit-per-company")).thenReturn(5000);
    quota = new CopyDailyQuota(redis, configs, CLOCK);
  }

  @Test
  void shouldKeyTheCounterByCompanyAndBeijingDate() {
    assertThat(quota.key(7L)).isEqualTo(KEY);
  }

  @Test
  void shouldReportUsage_withZeroWhenNothingCountedToday() {
    when(values.get(KEY)).thenReturn(null);

    assertThat(quota.usage(7L)).isEqualTo(new CopyDailyQuota.Usage(0, 5000));
    assertThat(quota.usage(7L).remaining()).isEqualTo(5000);
  }

  @Test
  void shouldAllowJob_thatExactlyFillsTheRemainingAllowance() {
    when(values.get(KEY)).thenReturn("4589");

    quota.requireRoom(7L, 411);
  }

  @Test
  void shouldRefuseJob_andTellTheRemainingAllowance() {
    when(values.get(KEY)).thenReturn("4800");

    assertThatThrownBy(() -> quota.requireRoom(7L, 411))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.COPY_DAILY_QUOTA_EXCEEDED);
              assertThat(be.getMessage())
                  .isEqualTo("本日复制额度不足：本日最多复制商品 5000 条，已复制 4800 条，剩余 200 条，本次提交 411 条");
            });
  }

  @Test
  void shouldReportNoRemaining_whenLimitWasLoweredBelowUsage() {
    when(values.get(KEY)).thenReturn("5200");
    when(configs.getInt("copy.daily-limit-per-company")).thenReturn(3000);

    assertThat(quota.usage(7L).remaining()).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldAcquire_whenScriptCounted() {
    when(redis.execute(any(RedisScript.class), eq(List.of(KEY)), eq("5000"), eq("172800")))
        .thenReturn(12L);

    assertThat(TenantScope.callCompany(7L, quota::tryAcquire)).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRefuseAcquire_whenScriptReportsLimitReached() {
    when(redis.execute(any(RedisScript.class), eq(List.of(KEY)), eq("5000"), eq("172800")))
        .thenReturn(-1L);

    assertThat(TenantScope.callCompany(7L, quota::tryAcquire)).isFalse();
  }

  @Test
  void shouldRequireCompanyScope_toAcquire() {
    assertThatThrownBy(quota::tryAcquire).isInstanceOf(MissingTenantException.class);
  }
}
