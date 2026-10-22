package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.api.ShopAccessDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WeidianConnectivityCheckerTest {
  private final WeidianHttpGateway gateway = mock(WeidianHttpGateway.class);
  private final WeidianRateLimiter limiter = mock(WeidianRateLimiter.class);
  private final WeidianConnectivityChecker checker =
      new WeidianConnectivityChecker(gateway, limiter, JsonMapper.builder().build());

  @Test
  void shouldCheckSingleItemAndReturnMatchingSellerHint() {
    when(gateway.apiPost("vdian.item.list.get", "1.1", "token", "{\"page_num\":1,\"page_size\":1}"))
        .thenReturn(
            JsonMapper.builder()
                .build()
                .readTree(
                    "{\"status\":{\"status_code\":0},\"result\":{\"items\":[{\"seller_id\":\"shop-4\"}]}}"));

    var result = checker.check(new ShopAccessDTO(4L, "shop", "shop-4", 1, null), "app", "token");

    assertThat(result.sellerId()).isEqualTo("shop-4");
    assertThat(result.sellerMatches()).isTrue();
    verify(limiter).acquire("app");
  }

  @Test
  void shouldReportSellerMismatchAsWarningSignal() {
    when(gateway.apiPost("vdian.item.list.get", "1.1", "token", "{\"page_num\":1,\"page_size\":1}"))
        .thenReturn(
            JsonMapper.builder()
                .build()
                .readTree(
                    "{\"status\":{\"status_code\":0},\"result\":{\"items\":[{\"seller_id\":\"different\"}]}}"));

    var result = checker.check(new ShopAccessDTO(4L, "shop", "shop-4", 1, null), "app", "token");

    assertThat(result.sellerMatches()).isFalse();
  }
}
