package com.tcrm.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tcrm.admin.common.BusinessException;
import com.tcrm.admin.security.LoginFailures;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LoginFailuresTest {
  @Test
  void locksAtFifthFailureAndBlocksLockedAccounts() {
    var redis = mock(StringRedisTemplate.class);
    var failures = new LoginFailures(redis);
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(1L, 2L, 3L, 4L, 5L);
    for (int i = 0; i < 4; i++) assertDoesNotThrow(() -> failures.fail("operator"));
    assertEquals(
        429, assertThrows(BusinessException.class, () -> failures.fail("operator")).getCode());
    when(redis.hasKey(anyString())).thenReturn(true);
    assertEquals(
        429, assertThrows(BusinessException.class, () -> failures.check("operator")).getCode());
    failures.clear("operator");
    verify(redis).delete(anyString());
  }

  @Test
  void usernameCaseSharesCounter() {
    var redis = mock(StringRedisTemplate.class);
    var failures = new LoginFailures(redis);
    failures.check("Operator");
    failures.check("operator");
    var keys = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(redis, times(2)).hasKey(keys.capture());
    assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
  }
}
