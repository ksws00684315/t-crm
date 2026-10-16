package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class KolEmailCodesTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final KolEmailCodes codes = new KolEmailCodes(redis);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void shouldFailClosed_whenTheCounterDoesNotIncrement() {
    when(values.increment(anyString())).thenReturn(null);
    assertThatThrownBy(() -> codes.requireSendIp("198.51.100.8"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REGISTER_TOO_FREQUENT.message());
  }

  @Test
  void shouldRejectThe21stSendFromOneIp_andStartTheWindowOnce() {
    when(values.increment(anyString())).thenReturn(1L, 21L);
    when(redis.getExpire(anyString())).thenReturn(-1L, 30L);
    codes.requireSendIp("198.51.100.8");
    verify(redis).expire(anyString(), eq(Duration.ofHours(1)));
    assertThatThrownBy(() -> codes.requireSendIp("198.51.100.8"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REGISTER_TOO_FREQUENT.message());
  }

  @Test
  void shouldRejectThe11thRegistrationForOneEmail() {
    when(values.increment(anyString())).thenReturn(11L);
    when(redis.getExpire(anyString())).thenReturn(10L);
    assertThatThrownBy(() -> codes.requireRegisterEmail("a@b.co"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_REGISTER_TOO_FREQUENT.message());
  }

  @Test
  void shouldRefuseASecondSendInsideTheCooldown() {
    when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(false);
    assertThatThrownBy(() -> codes.reserveSend(1L, "a@b.co"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_EMAIL_CODE_TOO_FREQUENT.message());
  }

  @Test
  void shouldStoreOnlyTheHashAndTheRemainingAttempts() {
    when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);
    codes.reserveSend(1L, "a@b.co");
    String code = codes.issue(1L, "a@b.co");
    assertThat(code).matches("\\d{6}");
    verify(values)
        .set(
            eq("kol:register:email-code:1:" + SecureTokens.hash("a@b.co")),
            eq(SecureTokens.hash(code) + ":" + KolEmailCodes.ATTEMPTS),
            eq(KolEmailCodes.CODE_TTL));
  }

  @Test
  void shouldAcceptACodeOnce_andRejectAnythingElse() {
    when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L, 0L, -1L, null);
    codes.consume(1L, "a@b.co", "123456");
    assertThatThrownBy(() -> codes.consume(1L, "a@b.co", "000000"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_EMAIL_CODE_INVALID.message());
    assertThatThrownBy(() -> codes.consume(1L, "a@b.co", "123456"))
        .hasMessage(ErrorCode.KOL_EMAIL_CODE_INVALID.message());
    assertThatThrownBy(() -> codes.consume(1L, "a@b.co", "123456"))
        .hasMessage(ErrorCode.KOL_EMAIL_CODE_INVALID.message());
  }
}
