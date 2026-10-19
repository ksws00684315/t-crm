package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KolCleanupItemServiceTest {
  private final KolCleanupItemMapper items = mock(KolCleanupItemMapper.class);
  private final KolItemLookupService links = mock(KolItemLookupService.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final KolCleanupWorker worker = mock(KolCleanupWorker.class);
  private KolCleanupItemService service;

  @BeforeAll
  static void initMybatisPlus() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, KolCleanupItemDO.class);
    TableInfoHelper.initTableInfo(assistant, ShopDO.class);
  }

  @BeforeEach
  void setUp() {
    service = new KolCleanupItemService(items, links, shops, worker);
  }

  @Test
  void shouldIncludeShopName_whenPaging() {
    KolCleanupItemDO row = new KolCleanupItemDO();
    row.setId(1L);
    row.setKolId(9L);
    row.setKolCode("KOL-1");
    row.setShopId(2L);
    row.setWeidianItemId("w1");
    row.setStatus("PENDING");
    row.setTriggerType("AUTO");
    row.setCleanupTime(Instant.now());
    Page<KolCleanupItemDO> page = new Page<>(1, 20);
    page.setRecords(List.of(row));
    page.setTotal(1);
    when(items.selectPage(any(), any())).thenReturn(page);
    ShopDO shop = new ShopDO();
    shop.setId(2L);
    shop.setShopName("专属店");
    when(shops.selectList(any())).thenReturn(List.of(shop));

    var result = service.page(null, null, null, 1, 20);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().shopName()).isEqualTo("专属店");
    assertThat(result.items().getFirst().shopId()).isEqualTo(2L);
  }

  @Test
  void shouldEnqueue_whenRetryClaimsFailedRow() {
    when(items.update(eq(null), any())).thenReturn(1);
    KolCleanupItemDO row = new KolCleanupItemDO();
    row.setId(3L);
    row.setKolId(9L);
    row.setStatus("PENDING");
    when(items.selectById(3L)).thenReturn(row);

    service.retryFailed(3L);

    verify(worker).submit(9L);
  }

  @Test
  void shouldRejectRetry_whenAtomicClaimMisses() {
    when(items.update(eq(null), any())).thenReturn(0);
    KolCleanupItemDO row = new KolCleanupItemDO();
    row.setId(3L);
    row.setStatus("PENDING");
    when(items.selectById(3L)).thenReturn(row);

    assertThatThrownBy(() -> service.retryFailed(3L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldEnqueueDeletesForKol() {
    service.enqueueDeletesForKol(9L);
    verify(worker).submit(9L);
  }
}
