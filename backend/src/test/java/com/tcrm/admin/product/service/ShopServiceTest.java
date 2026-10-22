package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.ShopIntegrationApi;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

class ShopServiceTest {
  private final ShopMapper shops = mock(ShopMapper.class);
  private final ShopIntegrationApi integration = mock(ShopIntegrationApi.class);
  private final SystemConfigApi configs = mock(SystemConfigApi.class);
  private final ShopService service =
      new ShopService(
          shops,
          mock(CurrentUserContext.class),
          mock(ApplicationEventPublisher.class),
          configs,
          integration);

  @ParameterizedTest
  @CsvSource({
    "0,AUTHORIZED,false,JOINED,0,DISABLED",
    "1,UNAUTHORIZED,false,JOINED,0,UNAUTHORIZED",
    "1,EXPIRED,false,JOINED,0,AUTH_EXPIRED",
    "1,AUTHORIZED,true,JOINED,0,SOURCE_SHOP",
    "1,AUTHORIZED,false,EXITED,0,POOL_EXITED",
    "1,AUTHORIZED,false,JOINED,10,CAPACITY_FULL"
  })
  void shouldExcludeEachIneligibleShopFromPoolAndExplainWhy(
      int status, String auth, boolean source, String pool, int current, String reason) {
    ShopDO shop = new ShopDO();
    shop.setId(37L);
    shop.setStatus(status);
    shop.setSource(source);
    shop.setCopyPoolStatus(pool);
    shop.setChannel("WEIDIAN");
    shop.setMaxItems(10);
    shop.setCurrentItems(current);
    Page<ShopDO> page = new Page<>(1, 20);
    page.setRecords(List.of(shop));
    page.setTotal(1);
    when(shops.selectPage(any(Page.class), any())).thenReturn(page);
    when(shops.selectList(any())).thenReturn(List.of(shop));
    Instant expires =
        "EXPIRED".equals(auth)
            ? Instant.parse("2000-01-01T00:00:00Z")
            : Instant.parse("2099-01-01T00:00:00Z");
    when(integration.statuses(List.of(37L)))
        .thenReturn(Map.of(37L, new ShopIntegrationApi.ShopAuthorizationDTO(auth, expires)));

    ShopDTO row = service.page(null, null, null, 1, 20).items().getFirst();

    assertThat(row.copyEligible()).isFalse();
    assertThat(row.copyIneligibleReason()).isEqualTo(reason);
    assertThat(service.copyChannels().getFirst().shops()).isEmpty();
    assertThat(service.copyChannels().getFirst().remainingCapacity()).isZero();
  }

  @Test
  void shouldListOptionsOrderedByShopCode() {
    ShopDO later = new ShopDO();
    later.setId(2L);
    later.setShopCode("S002");
    later.setShopName("乙");
    later.setChannel("WEIDIAN");
    later.setStatus(1);
    ShopDO earlier = new ShopDO();
    earlier.setId(1L);
    earlier.setShopCode("S001");
    earlier.setShopName("甲");
    earlier.setChannel("WEIDIAN");
    earlier.setStatus(0);
    when(shops.selectList(any())).thenReturn(List.of(earlier, later));

    assertThat(service.listOptions())
        .extracting(ShopOptionDTO::shopCode)
        .containsExactly("S001", "S002");
  }

  @Test
  void shouldReturnCopyChannelDefaultsFromSystemConfig() {
    when(shops.selectList(any())).thenReturn(List.of());
    when(configs.getInt("copy-token.default-quota")).thenReturn(1);
    when(configs.getInt("copy-token.default-valid-minutes")).thenReturn(1440);

    var channel = service.copyChannels().getFirst();
    assertThat(channel.channel()).isEqualTo("WEIDIAN");
    assertThat(channel.defaultQuota()).isEqualTo(1);
    assertThat(channel.defaultValidMinutes()).isEqualTo(1440);

    when(configs.getInt("copy-token.default-quota")).thenReturn(17);
    when(configs.getInt("copy-token.default-valid-minutes")).thenReturn(240);
    var updated = service.copyChannels().getFirst();
    assertThat(updated.defaultQuota()).isEqualTo(17);
    assertThat(updated.defaultValidMinutes()).isEqualTo(240);
  }
}
