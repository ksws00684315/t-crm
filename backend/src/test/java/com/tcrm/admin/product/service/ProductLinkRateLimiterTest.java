package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ProductLinkRateLimiterTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private ProductLinkRateLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter = new ProductLinkRateLimiter(redis, 2, 2, 2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRejectIp_whenOverLimit() {
    when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

    assertThatThrownBy(() -> limiter.checkIp("10.0.0.1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_LINK_RATE_LIMITED);
              assertThat(be.getMessage()).isEqualTo(ProductLinkMessages.RATE_LIMITED);
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRejectConvertToken_whenOverLimit() {
    when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

    assertThatThrownBy(() -> limiter.checkConvertToken(9L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.PRODUCT_LINK_RATE_LIMITED);
  }
}
