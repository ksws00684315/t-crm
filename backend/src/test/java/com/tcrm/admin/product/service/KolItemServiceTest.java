package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRateDTO;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemSalesQuery;
import com.tcrm.admin.product.api.KolItemSalesTotal;
import com.tcrm.admin.product.api.KolItemVisitQuery;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolCleanupLatestRow;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolItemServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolItemDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShopDO.class);
  }

  private final KolItemMapper items = mock(KolItemMapper.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final KolCleanupItemMapper cleanups = mock(KolCleanupItemMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final KolItemSalesQuery sales = mock(KolItemSalesQuery.class);
  private final KolItemVisitQuery visits = mock(KolItemVisitQuery.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private KolItemService service;

  @BeforeEach
  void setup() {
    service = new KolItemService(items, products, shops, cleanups, kols, sales, visits, current);
    when(current.user()).thenReturn(admin());
  }

  @Test
  void shouldListWithoutPrecisionFilter_andOrderByCreateTime() {
    when(items.selectPage(any(), any())).thenAnswer(inv -> emptyPage(inv.getArgument(0)));

    var result = service.page(new KolItemFilterDTO(null, null, null, 1, 20));

    assertThat(result.total()).isZero();
    String sql = capturedQuery().getSqlSegment().toLowerCase();
    assertThat(sql).contains("create_time").contains("desc");
    verify(sales, never()).sumByKolItemIds(any());
    verifyNoInteractions(visits);
  }

  @Test
  void shouldUppercaseLinkCode() {
    when(items.selectPage(any(), any())).thenAnswer(inv -> emptyPage(inv.getArgument(0)));

    service.page(new KolItemFilterDTO("kpl-abcdef123456", null, null, 1, 20));

    assertThat(paramText(capturedQuery())).contains("KPL-ABCDEF123456");
  }

  @Test
  void shouldLimitOwnScopeToAssignedKols() {
    when(current.user()).thenReturn(bd());
    when(kols.listByBdUserId(7L))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL-TAA001", "Alice", true, 7L)));
    when(items.selectPage(any(), any())).thenAnswer(inv -> emptyPage(inv.getArgument(0)));

    service.page(new KolItemFilterDTO(null, null, null, 1, 20));

    Wrapper<?> query = capturedQuery();
    assertThat(query.getSqlSegment()).contains("kol_id IN");
    assertThat(paramText(query)).contains("11");
  }

  @Test
  void shouldReturnEmptyWithoutQuery_whenOwnScopeHasNoAssignedKols() {
    when(current.user()).thenReturn(bd());
    when(kols.listByBdUserId(7L)).thenReturn(List.of());

    var result = service.page(new KolItemFilterDTO(null, null, null, 1, 20));

    assertThat(result.items()).isEmpty();
    verifyNoInteractions(items, sales, visits);
  }

  @Test
  void shouldReturnEmptyWithoutQuery_whenKeywordMatchesNobody() {
    when(kols.findIdsByKeyword("nobody")).thenReturn(List.of());

    var result = service.page(new KolItemFilterDTO(null, "nobody", null, 1, 20));

    assertThat(result.items()).isEmpty();
    verifyNoInteractions(items);
  }

  @Test
  void shouldIntersectKeywordWithOwnScope() {
    when(current.user()).thenReturn(bd());
    when(kols.listByBdUserId(7L))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL-TAA001", "Alice", true, 7L)));
    when(kols.findIdsByKeyword("alice")).thenReturn(List.of(11L, 12L));
    when(items.selectPage(any(), any())).thenAnswer(inv -> emptyPage(inv.getArgument(0)));

    service.page(new KolItemFilterDTO(null, "alice", null, 1, 20));

    String params = paramText(capturedQuery());
    assertThat(params).contains("11");
    assertThat(params).doesNotContain("12");
  }

  @Test
  void shouldAggregateThePageOnce_andSkipVisits() {
    KolItemDO enabled = row(1L, 11L, 21L, 31L, null);
    KolItemDO disabled = row(2L, 12L, null, 31L, Instant.parse("2020-01-01T00:00:00Z"));
    disabled.setItemName("Snapshot");
    disabled.setWeidianItemId("9002");
    when(items.selectPage(any(), any()))
        .thenAnswer(
            inv -> {
              Page<KolItemDO> page = inv.getArgument(0);
              page.setRecords(List.of(enabled, disabled));
              page.setTotal(2);
              return page;
            });
    when(kols.listByIds(anyCollection()))
        .thenReturn(
            List.of(
                new KolRefDTO(11L, "KOL-TAA001", "Alice", true, null),
                new KolRefDTO(12L, "KOL-TBB001", "Bob", true, null)));
    when(kols.listRatesByIds(anyCollection()))
        .thenReturn(List.of(new KolRateDTO(11L, "0.1500"), new KolRateDTO(12L, null)));
    ProductDO product = new ProductDO();
    product.setId(21L);
    product.setName("Catalog name");
    product.setImageUrl("https://example.com/t39.png");
    product.setWeidianItemId("8001");
    when(products.selectList(any())).thenReturn(List.of(product));
    ShopDO shop = new ShopDO();
    shop.setId(31L);
    shop.setShopName("Shop A");
    when(shops.selectList(any())).thenReturn(List.of(shop));
    when(sales.sumByKolItemIds(anyCollection()))
        .thenReturn(
            List.of(
                new KolItemSalesTotal(1L, 3L, new BigDecimal("14.000"), new BigDecimal("3.5"))));
    when(cleanups.listByKolItemIds(anyCollection()))
        .thenReturn(
            List.of(cleanup(1L, "SUCCESS", "done", null), cleanup(2L, "FAILED", "why", "api")));

    var result = service.page(new KolItemFilterDTO(null, null, null, 1, 20));

    verify(sales).sumByKolItemIds(List.of(1L, 2L));
    verifyNoInteractions(visits);
    KolItemView first = result.items().get(0);
    assertThat(first.itemName()).isEqualTo("Catalog name");
    assertThat(first.imageUrl()).isEqualTo("https://example.com/t39.png");
    assertThat(first.sourceItemId()).isEqualTo("8001");
    assertThat(first.sourceChannel()).isEqualTo("WEIDIAN");
    assertThat(first.targetChannel()).isEqualTo("WEIDIAN");
    assertThat(first.targetItemUrl()).isEqualTo("https://weidian.com/item.html?itemID=9001");
    assertThat(first.currentRate()).isEqualTo("0.1500");
    assertThat(first.salesQuantity()).isEqualTo(3L);
    assertThat(first.payAmount()).isEqualByComparingTo("14.00");
    assertThat(first.status()).isEqualTo("ENABLED");
    assertThat(first.cleanupStatus()).isEqualTo("DONE");
    assertThat(first.targetShopCode()).isNull();
    assertThat(first.targetShopName()).isEqualTo("Shop A");
    KolItemView second = result.items().get(1);
    assertThat(second.itemName()).isEqualTo("Snapshot");
    assertThat(second.imageUrl()).isNull();
    assertThat(second.sourceChannel()).isNull();
    assertThat(second.salesQuantity()).isZero();
    assertThat(second.payAmount()).isEqualByComparingTo("0.00");
    assertThat(second.status()).isEqualTo("DISABLED");
    assertThat(second.cleanupStatus()).isEqualTo("FAILED");
  }

  @Test
  void shouldReturn404_whenDetailIsMissing() {
    when(items.selectById(5L)).thenReturn(null);

    assertThatThrownBy(() -> service.detail(5L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
    verifyNoInteractions(sales, visits);
  }

  @Test
  void shouldReturn404_whenOwnScopeCannotSeeTheKol() {
    when(current.user()).thenReturn(bd());
    when(kols.listByBdUserId(7L))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL-TAA001", "Alice", true, 7L)));
    when(items.selectById(2L)).thenReturn(row(2L, 99L, null, 31L, null));

    assertThatThrownBy(() -> service.detail(2L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
    verifyNoInteractions(sales, visits);
  }

  @Test
  void shouldIncludeVisitsAndRefund_onDetail() {
    KolItemDO row = row(1L, 11L, 21L, 31L, null);
    when(items.selectById(1L)).thenReturn(row);
    when(kols.listByIds(anyCollection()))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL-TAA001", "Alice", true, null)));
    when(kols.listRatesByIds(anyCollection())).thenReturn(List.of(new KolRateDTO(11L, "0.1500")));
    ProductDO product = new ProductDO();
    product.setId(21L);
    product.setName("Catalog name");
    product.setWeidianItemId("8001");
    when(products.selectList(any())).thenReturn(List.of(product));
    ShopDO shop = new ShopDO();
    shop.setId(31L);
    shop.setShopName("Shop A");
    when(shops.selectList(any())).thenReturn(List.of(shop));
    when(sales.sumByKolItemIds(anyCollection()))
        .thenReturn(
            List.of(new KolItemSalesTotal(1L, 3L, new BigDecimal("14"), new BigDecimal("3.5"))));
    when(visits.countByKolItemIds(anyCollection())).thenReturn(List.of());
    Instant cleaned = Instant.parse("2026-10-20T00:00:00Z");
    KolCleanupLatestRow cleanup = cleanup(1L, "FAILED", "closed", "token rejected");
    cleanup.setCleanupTime(cleaned);
    when(cleanups.listByKolItemIds(anyCollection())).thenReturn(List.of(cleanup));

    KolItemDetail detail = service.detail(1L);

    verify(visits).countByKolItemIds(List.of(1L));
    assertThat(detail.visitCount()).isZero();
    assertThat(detail.totalPayAmount()).isEqualByComparingTo("14.00");
    assertThat(detail.refundAmount()).isEqualByComparingTo("3.50");
    assertThat(detail.sourceItemUrl()).isEqualTo("https://weidian.com/item.html?itemID=8001");
    assertThat(detail.cleanupTime()).isEqualTo(cleaned);
    assertThat(detail.cleanupReason()).isEqualTo("closed");
    assertThat(detail.cleanupFailReason()).isEqualTo("token rejected");
    assertThat(detail.item().cleanupStatus()).isEqualTo("FAILED");
  }

  private Wrapper<KolItemDO> capturedQuery() {
    ArgumentCaptor<Wrapper<KolItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).selectPage(any(), captor.capture());
    return captor.getValue();
  }

  private static String paramText(Wrapper<?> query) {
    // Parameter values are written when the SQL segment is rendered.
    query.getSqlSegment();
    return String.valueOf(((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs().values());
  }

  private static Page<KolItemDO> emptyPage(Page<KolItemDO> page) {
    page.setRecords(List.of());
    page.setTotal(0);
    return page;
  }

  private static KolItemDO row(long id, long kolId, Long productId, long shopId, Instant validTo) {
    KolItemDO row = new KolItemDO();
    row.setId(id);
    row.setKolId(kolId);
    row.setProductId(productId);
    row.setShopId(shopId);
    row.setWeidianItemId("9001");
    row.setLinkCode("KPL-ABCDEF123456");
    row.setValidFrom(Instant.parse("2020-01-01T00:00:00Z"));
    row.setValidTo(validTo);
    row.setCreateTime(Instant.parse("2026-10-21T00:00:00Z"));
    return row;
  }

  private static KolCleanupLatestRow cleanup(
      long kolItemId, String status, String reason, String failReason) {
    KolCleanupLatestRow row = new KolCleanupLatestRow();
    row.setKolItemId(kolItemId);
    row.setStatus(status);
    row.setReason(reason);
    row.setFailReason(failReason);
    row.setCleanupTime(Instant.parse("2026-10-01T00:00:00Z"));
    return row;
  }

  private static AccountDTO admin() {
    return new AccountDTO(
        1L,
        "admin",
        null,
        "Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        null,
        null,
        true);
  }

  private static AccountDTO bd() {
    return new AccountDTO(
        7L, "bd", null, "BD", null, Role.STAFF, DataScope.OWN, 1, null, null, 0, 1L, null, true);
  }
}
