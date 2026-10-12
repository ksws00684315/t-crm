package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeidianTokenRenewalJobTest {
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final WeidianShopAuthMapper auths = mock(WeidianShopAuthMapper.class);
  private final WeidianTokenCenter tokens = mock(WeidianTokenCenter.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);

  @Test
  void shouldSkipDisabledTenantsAndShopsAndContinueAfterOneCompanyFails()
      throws InterruptedException {
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L, 3L));
    when(shops.listShops())
        .thenAnswer(
            ignored ->
                switch (TenantScope.companyId().intValue()) {
                  case 1 ->
                      List.of(
                          new ShopAccessDTO(11L, "enabled", "weidian-11", 1),
                          new ShopAccessDTO(12L, "disabled", "weidian-12", 0));
                  case 3 -> List.of(new ShopAccessDTO(31L, "other company", "weidian-31", 1));
                  default -> throw new AssertionError("unexpected company scope");
                });
    when(auths.selectList(any(Wrapper.class)))
        .thenAnswer(
            ignored ->
                TenantScope.companyId() == 1L
                    ? List.of(authorized(11L), authorized(12L))
                    : List.of(authorized(31L)));
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(3)).run();
              return true;
            })
        .when(locks)
        .run(any(String.class), anyLong(), anyLong(), any(Runnable.class));
    doAnswer(
            invocation -> {
              long shopId = invocation.getArgument(0);
              if (shopId == 11L) {
                throw new IllegalStateException("simulated company refresh failure");
              }
              return null;
            })
        .when(tokens)
        .renewIfDue(anyLong());

    WeidianTokenRenewalJob job = new WeidianTokenRenewalJob(tenants, shops, auths, tokens, locks);
    job.renewEnabledCompanies();

    verify(tokens).renewIfDue(11L);
    verify(tokens).renewIfDue(31L);
    verify(tokens, never()).renewIfDue(12L);
    assertThat(TenantScope.companyId()).isNull();
  }

  private WeidianShopAuthDO authorized(long shopId) {
    WeidianShopAuthDO row = new WeidianShopAuthDO();
    row.setShopId(shopId);
    row.setStatus(WeidianAuthStatus.AUTHORIZED.name());
    return row;
  }
}
