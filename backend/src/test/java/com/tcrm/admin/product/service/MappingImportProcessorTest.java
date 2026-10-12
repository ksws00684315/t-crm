package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.task.api.ImportReason;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class MappingImportProcessorTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolItemDO.class);
  }

  private final ShopService shops = mock(ShopService.class);
  private final ProductService products = mock(ProductService.class);
  private final KolItemMapper items = mock(KolItemMapper.class);
  private final ProductMapper productMapper = mock(ProductMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final TransactionTemplate tx = TestTransactions.template();
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private MappingImportProcessor processor;
  private ShopDO shop;
  private ProductDO product;
  private KolRefDTO kol;

  @BeforeEach
  void setup() {
    processor =
        new MappingImportProcessor(9L, shops, products, items, productMapper, kols, tx, events);
    shop = new ShopDO();
    shop.setId(1L);
    shop.setWeidianShopId("100");
    shop.setStatus(1);
    when(shops.findEnabledByWeidianId("100")).thenReturn(shop);
    product = new ProductDO();
    product.setId(20L);
    product.setProductCode("P000001");
    product.setName("鞋");
    when(products.findByCode("P000001")).thenReturn(product);
    kol = new KolRefDTO(10L, "KOL0001", "Alice", true, null);
    when(kols.getByCode("KOL0001")).thenReturn(kol);
    doAnswer(
            inv -> {
              KolItemDO row = inv.getArgument(0);
              row.setId(99L);
              return 1;
            })
        .when(items)
        .insert(any(KolItemDO.class));
  }

  private static List<String> row(String shopId, String item, String kolCode, String productCode) {
    return new ArrayList<>(List.of(shopId, item, kolCode, productCode));
  }

  @Test
  void shouldInsert_whenNewMapping() {
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001"))).isEmpty();
    ArgumentCaptor<KolItemDO> cap = ArgumentCaptor.forClass(KolItemDO.class);
    verify(items).insert(cap.capture());
    assertThat(cap.getValue().getKolId()).isEqualTo(10L);
    assertThat(cap.getValue().getProductId()).isEqualTo(20L);
    assertThat(cap.getValue().getWeidianItemId()).isEqualTo("9001");
    assertThat(cap.getValue().getValidTo()).isNull();
  }

  @Test
  void shouldPublishMappingChangedEvent_whenMappingInserted() {
    processor.process(row("100", "9001", "KOL0001", "P000001"));
    verify(events).publishEvent(new KolItemChangedEvent(10L, 20L));
  }

  @Test
  void shouldSucceedIdempotently_whenIdenticalCurrentMappingExists() {
    KolItemDO current = new KolItemDO();
    current.setId(5L);
    current.setKolId(10L);
    current.setProductId(20L);
    current.setShopId(1L);
    current.setWeidianItemId("9001");
    when(items.findCurrentByShopItem(1L, "9001")).thenReturn(current);
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001"))).isEmpty();
    verify(items, never()).insert(any(KolItemDO.class));
    verify(items, never()).update(any(), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void shouldReject_whenExclusiveItemIsOriginalProduct() {
    when(productMapper.existsAliveShopItem(1L, "9001")).thenReturn(true);
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001")))
        .contains(ImportReason.of(MappingImportProcessor.IS_ORIGINAL));
    verify(productMapper, never()).lockShopItem(any());
  }

  @Test
  void shouldReject_whenItemBecomesOriginalProductAfterTheLock() {
    when(productMapper.existsAliveShopItem(1L, "9001")).thenReturn(false, true);
    KolItemDO old = new KolItemDO();
    old.setId(5L);
    old.setKolId(10L);
    old.setProductId(20L);
    old.setShopId(1L);
    old.setWeidianItemId("8000");
    when(items.findCurrentByKolProduct(10L, 20L)).thenReturn(old);
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001")))
        .contains(ImportReason.of(MappingImportProcessor.IS_ORIGINAL));
    verify(productMapper).lockShopItem("crm:shop-item:1:9001");
    verify(items, never()).update(any(), any());
    verify(items, never()).insert(any(KolItemDO.class));
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void shouldReject_whenExclusiveItemOccupiedByOther() {
    KolItemDO current = new KolItemDO();
    current.setId(5L);
    current.setKolId(11L);
    current.setProductId(21L);
    current.setShopId(1L);
    current.setWeidianItemId("9001");
    when(items.findCurrentByShopItem(1L, "9001")).thenReturn(current);
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(11L, "KOL0002", "Bob", true, null)));
    ProductDO other = new ProductDO();
    other.setProductCode("P000002");
    when(productMapper.selectById(21L)).thenReturn(other);
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001")))
        .containsExactly(ImportReason.of(MappingImportProcessor.OCCUPIED, "KOL0002", "P000002"));
  }

  @Test
  void shouldReplaceExclusiveItem_whenSameKolProductDifferentItem() {
    KolItemDO old = new KolItemDO();
    old.setId(5L);
    old.setKolId(10L);
    old.setProductId(20L);
    old.setShopId(1L);
    old.setWeidianItemId("8000");
    when(items.findCurrentByKolProduct(10L, 20L)).thenReturn(old);
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001"))).isEmpty();
    verify(items).update(any(), any());
    verify(items).insert(any(KolItemDO.class));
  }

  @Test
  void shouldRejectDuplicateExclusive_inSameFile() {
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001"))).isEmpty();
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001")))
        .contains(ImportReason.of(MappingImportProcessor.ITEM_DUPLICATE));
  }

  @Test
  void shouldRejectDisabledKol() {
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "Alice", false, null));
    assertThat(processor.process(row("100", "9001", "KOL0001", "P000001")))
        .contains(ImportReason.of(MappingImportProcessor.KOL_MISSING));
  }
}
