package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.api.SystemConfigApi;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class KolRegistrationRateLimiterTest {
  @SuppressWarnings("unchecked")
  @Test
  void shouldApplyUpdatedRegistrationLimitOnNextRequest() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    SystemConfigApi configs = mock(SystemConfigApi.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L, 2L, 3L);
    when(redis.getExpire(org.mockito.ArgumentMatchers.anyString())).thenReturn(60L);
    when(configs.getInt("kol.register.ip-per-hour")).thenReturn(1, 2);
    KolRegistrationRateLimiter limiter = new KolRegistrationRateLimiter(redis, configs);

    limiter.requireRegisterIp("192.0.2.1");
    limiter.requireRegisterIp("192.0.2.1");
    when(configs.getInt("kol.register.ip-per-hour")).thenReturn(2);
    assertThatThrownBy(() -> limiter.requireRegisterIp("192.0.2.1"))
        .isInstanceOf(BusinessException.class);
    verify(configs, org.mockito.Mockito.times(3)).getInt("kol.register.ip-per-hour");
  }
}
