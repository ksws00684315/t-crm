package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginFailuresTest {
  @Test
  void shouldLocksAtFifthFailureAndBlocksLockedAccounts_whenRequested() {
    var redis = mock(StringRedisTemplate.class);
    var failures = new LoginFailures(redis);
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(1L, 2L, 3L, 4L, 5L);
    for (int i = 0; i < 4; i++) {
      assertThatCode(() -> failures.fail("operator")).doesNotThrowAnyException();
    }
    assertThat(
            catchThrowableOfType(() -> failures.fail("operator"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
    when(redis.hasKey(anyString())).thenReturn(true);
    assertThat(
            catchThrowableOfType(() -> failures.check("operator"), BusinessException.class)
                .getCode())
        .isEqualTo(429);
    failures.clear("operator");
    verify(redis).delete(anyString());
  }

  @Test
  void shouldUsernameCaseSharesCounter_whenRequested() {
    var redis = mock(StringRedisTemplate.class);
    var failures = new LoginFailures(redis);
    failures.check("Operator");
    failures.check("operator");
    var keys = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(redis, times(2)).hasKey(keys.capture());
    assertThat(keys.getAllValues().get(1)).isEqualTo(keys.getAllValues().get(0));
  }

  @Test
  void shouldAcceptLoginCheck_whenCounterIsMissingOrUnlocked() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.hasKey(anyString())).thenReturn(false, null);
    var failures = new LoginFailures(redis);
    assertThatCode(() -> failures.check("operator")).doesNotThrowAnyException();
    assertThatCode(() -> failures.check("operator")).doesNotThrowAnyException();
  }

  @Test
  void shouldKeepAuthenticationAvailable_whenRedisScriptReturnsNoCount() {
    var redis = mock(StringRedisTemplate.class);
    var failures = new LoginFailures(redis);
    assertThatCode(() -> failures.fail("operator")).doesNotThrowAnyException();
  }
}
