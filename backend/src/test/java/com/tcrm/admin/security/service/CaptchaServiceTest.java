package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class CaptchaServiceTest {
  private static final String ID = "6f1c2a3b-0000-4000-8000-000000000001";
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final CaptchaImageGenerator generator = mock(CaptchaImageGenerator.class);
  private final CaptchaService captchas = new CaptchaService(redis, generator, true);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    when(generator.generate())
        .thenReturn(new CaptchaImageGenerator.Captcha("AB7K", new byte[] {1, 2, 3}));
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
  }

  @Test
  void shouldStoreAnswerWithShortTtl_whenIssuing() {
    var issued = captchas.issue("198.51.100.8");
    assertThat(issued.enabled()).isTrue();
    assertThat(issued.image()).isEqualTo("data:image/png;base64,AQID");
    assertThat(issued.expiresIn()).isEqualTo(180);
    verify(values).set("auth:captcha:" + issued.captchaId(), "AB7K", Duration.ofMinutes(3));
    assertThat(CaptchaService.TTL).isBetween(Duration.ofMinutes(2), Duration.ofMinutes(5));
  }

  @Test
  void shouldIssueDistinctIds_whenIssuingTwice() {
    assertThat(captchas.issue("198.51.100.8").captchaId())
        .isNotEqualTo(captchas.issue("198.51.100.8").captchaId());
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRateLimitPerHashedIp_whenIssuing() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn((long) CaptchaService.ISSUE_LIMIT + 1);
    assertThatThrownBy(() -> captchas.issue("198.51.100.8"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_CAPTCHA_RATE_LIMITED.message());
    var keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(any(RedisScript.class), keys.capture(), eq("60000"));
    assertThat((List<String>) keys.getValue())
        .singleElement()
        .asString()
        .startsWith("auth:captcha:rate:")
        .doesNotContain("198.51.100.8");
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void shouldAcceptAnyCase_whenVerifying() {
    when(values.getAndDelete("auth:captcha:" + ID)).thenReturn("AB7K", "AB7K");
    assertThatCode(() -> captchas.verify(ID, "ab7k")).doesNotThrowAnyException();
    assertThatCode(() -> captchas.verify(ID, " Ab7K ")).doesNotThrowAnyException();
  }

  @Test
  void shouldConsumeCaptcha_evenWhenAnswerIsWrong() {
    when(values.getAndDelete("auth:captcha:" + ID)).thenReturn("AB7K");
    assertThatThrownBy(() -> captchas.verify(ID, "ZZZZ"))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
    verify(values).getAndDelete("auth:captcha:" + ID);
  }

  @Test
  void shouldRejectReuse_whenCaptchaWasAlreadyConsumed() {
    when(values.getAndDelete("auth:captcha:" + ID)).thenReturn("AB7K", (String) null);
    captchas.verify(ID, "AB7K");
    assertThatThrownBy(() -> captchas.verify(ID, "AB7K"))
        .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
  }

  @Test
  void shouldRejectExpired_whenKeyIsGone() {
    when(values.getAndDelete("auth:captcha:" + ID)).thenReturn(null);
    assertThatThrownBy(() -> captchas.verify(ID, "AB7K"))
        .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
  }

  @Test
  void shouldRejectWithoutRedisLookup_whenIdIsMissingOrMalformed() {
    for (String id : new String[] {null, "", "auth:captcha:x", "not-a-uuid"}) {
      assertThatThrownBy(() -> captchas.verify(id, "AB7K"))
          .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
    }
    verify(values, never()).getAndDelete(anyString());
  }

  @Test
  void shouldConsumeAndReject_whenCodeIsMissing() {
    when(values.getAndDelete("auth:captcha:" + ID)).thenReturn("AB7K");
    assertThatThrownBy(() -> captchas.verify(ID, null))
        .hasMessage(ErrorCode.AUTH_CAPTCHA_INVALID.message());
    verify(values).getAndDelete("auth:captcha:" + ID);
  }

  @Test
  void shouldSkipEverything_whenDisabled() {
    var off = new CaptchaService(redis, generator, false);
    assertThat(off.issue("198.51.100.8").enabled()).isFalse();
    assertThatCode(() -> off.verify(null, null)).doesNotThrowAnyException();
    verifyNoInteractions(generator);
    verify(redis, never()).opsForValue();
  }
}
