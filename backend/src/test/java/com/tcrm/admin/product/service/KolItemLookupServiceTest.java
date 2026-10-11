package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemLinkRow;
import com.tcrm.admin.product.model.ProductDO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class KolItemLookupServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolItemDO.class);
  }

  private final KolItemMapper items = mock(KolItemMapper.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final KolItemLookupService service = new KolItemLookupService(items, products);

  private static KolItemLinkRow row(Integer productStatus, Integer shopStatus) {
    KolItemLinkRow row = new KolItemLinkRow();
    row.setKolItemId(5L);
    row.setKolId(10L);
    row.setProductId(20L);
    row.setProductCode("P000001");
    row.setProductName("鞋");
    row.setShopId(1L);
    row.setWeidianItemId("9001");
    row.setProductStatus(productStatus);
    row.setShopStatus(shopStatus);
    return row;
  }

  @Test
  void shouldPageKolIds_whenRefreshingTheLinkIndex() {
    when(items.pageKolIdsWithCurrentProduct(20L, null, 200)).thenReturn(List.of(10L, 11L));
    when(items.pageKolIdsWithCurrentShop(1L, 10L, 200)).thenReturn(List.of(11L));

    assertThat(service.pageKolIdsWithCurrentProduct(20L, null, 200)).containsExactly(10L, 11L);
    assertThat(service.pageKolIdsWithCurrentShop(1L, 10L, 200)).containsExactly(11L);
  }

  @Test
  void shouldMapStatusesToFlags_whenListingByKol() {
    when(items.listCurrentLinksByKol(10L)).thenReturn(List.of(row(1, 0)));
    assertThat(service.listCurrentLinksByKol(10L))
        .containsExactly(new KolItemLinkDTO(5L, 10L, 20L, "P000001", "鞋", 1L, "9001", true, false));
  }

  @Test
  void shouldTreatMissingStatusAsUnusable() {
    when(items.listCurrentLinksByProduct(20L)).thenReturn(List.of(row(null, null)));
    KolItemLinkDTO link = service.listCurrentLinksByProduct(20L).getFirst();
    assertThat(link.productOnSale()).isFalse();
    assertThat(link.shopEnabled()).isFalse();
  }

  @Test
  void shouldFilterByShop_whenListingByKolAndShop() {
    when(items.listCurrentLinksByKolAndShop(10L, 1L)).thenReturn(List.of(row(1, 1)));
    assertThat(service.listCurrentLinksByKolAndShop(10L, 1L)).hasSize(1);
  }

  @Test
  void shouldReturnNull_whenPairHasNoCurrentMapping() {
    assertThat(service.getCurrentLink(10L, 20L)).isNull();
    when(items.getCurrentLink(10L, 20L)).thenReturn(row(1, 1));
    assertThat(service.getCurrentLink(10L, 20L).kolItemId()).isEqualTo(5L);
  }

  @Test
  void shouldUpperCaseProductCode_whenPaging() {
    Page<KolItemLinkRow> page = new Page<>(1, 20);
    page.setRecords(List.of(row(1, 1)));
    page.setTotal(1);
    when(items.pageCurrentLinksByKol(any(), eq(10L), eq("P000001"))).thenReturn(page);
    var result = service.pageCurrentLinksByKol(10L, " p000001 ", 1, 20);
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items()).hasSize(1);
  }

  @Test
  void shouldPassNullCode_whenPagingWithoutProductFilter() {
    Page<KolItemLinkRow> page = new Page<>(2, 10);
    when(items.pageCurrentLinksByKol(any(), eq(10L), isNull())).thenReturn(page);
    var result = service.pageCurrentLinksByKol(10L, " ", 2, 10);
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.items()).isEmpty();
    verify(items).pageCurrentLinksByKol(any(), eq(10L), isNull());
  }

  @Test
  void shouldReturnProductCode_orNullWhenUnknown() {
    ProductDO product = new ProductDO();
    product.setProductCode("P000009");
    when(products.selectById(9L)).thenReturn(product);
    assertThat(service.getProductCode(9L)).isEqualTo("P000009");
    assertThat(service.getProductCode(8L)).isNull();
  }

  @Test
  void shouldResolveRefs_andSkipQueryForEmptyIds() {
    KolItemDO item = new KolItemDO();
    item.setId(5L);
    item.setKolId(10L);
    item.setProductId(20L);
    when(items.selectList(any())).thenReturn(List.of(item));
    assertThat(service.listRefsByIds(List.of(5L)))
        .containsExactly(new KolItemRefDTO(5L, 10L, 20L, null));
    KolItemMapper unused = mock(KolItemMapper.class);
    assertThat(new KolItemLookupService(unused, products).listRefsByIds(List.of())).isEmpty();
    assertThat(new KolItemLookupService(unused, products).listRefsByIds(null)).isEmpty();
    verifyNoInteractions(unused);
  }
}
