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
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

class ProductImportProcessorTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductDO.class);
  }

  private final ShopService shops = mock(ShopService.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final KolItemMapper kolItems = mock(KolItemMapper.class);
  private final ProductCodeGenerator codes = mock(ProductCodeGenerator.class);
  private final TransactionTemplate tx = new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class));
  private ProductImportProcessor processor;
  private ShopDO shop;

  @BeforeEach
  void setup() {
    processor = new ProductImportProcessor(9L, shops, products, kolItems, codes, tx);
    shop = new ShopDO();
    shop.setId(1L);
    shop.setWeidianShopId("100");
    shop.setStatus(1);
    when(shops.findEnabledByWeidianId("100")).thenReturn(shop);
    when(codes.next()).thenReturn("P000001");
    doAnswer(
            inv -> {
              ProductDO p = inv.getArgument(0);
              p.setId(50L);
              return 1;
            })
        .when(products)
        .insert(any(ProductDO.class));
  }

  private static List<String> row(String... cells) {
    List<String> r = new ArrayList<>(List.of(cells));
    while (r.size() < ProductImportColumn.values().length) {
      r.add("");
    }
    return r;
  }

  @Test
  void shouldInsert_whenNewShopItem() {
    when(products.selectOne(any())).thenReturn(null);
    assertThat(processor.process(row("", "100", "2001", "鞋", "类", "https://a.com/x.png", "12.5", "10")))
        .isEmpty();
    ArgumentCaptor<ProductDO> cap = ArgumentCaptor.forClass(ProductDO.class);
    verify(products).insert(cap.capture());
    assertThat(cap.getValue().getProductCode()).isEqualTo("P000001");
    assertThat(cap.getValue().getName()).isEqualTo("鞋");
    assertThat(cap.getValue().getPrice()).isEqualByComparingTo("12.50");
    assertThat(cap.getValue().getRate()).isEqualByComparingTo("0.1000");
    assertThat(cap.getValue().getStatus()).isEqualTo(1);
  }

  @Test
  void shouldUpdateLeavingBlanksUnchanged_whenExisting() {
    ProductDO existing = new ProductDO();
    existing.setId(7L);
    existing.setProductCode("P000009");
    existing.setShopId(1L);
    existing.setWeidianItemId("2001");
    existing.setName("旧名");
    existing.setPrice(new BigDecimal("1.00"));
    when(products.selectOne(any())).thenReturn(existing);
    assertThat(processor.process(row("P000009", "100", "2001", "新名", "", "", "", "")))
        .isEmpty();
    verify(products).update(any(), any());
    verify(products, never()).insert(any(ProductDO.class));
  }

  @Test
  void shouldReject_whenCodeWouldChangeOnUpdate() {
    ProductDO existing = new ProductDO();
    existing.setId(7L);
    existing.setProductCode("P000009");
    existing.setShopId(1L);
    existing.setWeidianItemId("2001");
    when(products.selectOne(any())).thenReturn(existing);
    assertThat(processor.process(row("P000010", "100", "2001", "名", "", "", "1", "")))
        .contains(ProductImportProcessor.CODE_IMMUTABLE);
  }

  @Test
  void shouldReject_whenExclusiveItemRegisteredAsProduct() {
    when(products.selectOne(any())).thenReturn(null);
    when(kolItems.existsCurrentShopItem(1L, "2001")).thenReturn(true);
    assertThat(processor.process(row("", "100", "2001", "鞋", "", "", "10", "")))
        .contains(ProductImportProcessor.EXCLUSIVE);
  }

  @Test
  void shouldRejectDuplicateShopItem_inSameFile() {
    when(products.selectOne(any())).thenReturn(null);
    assertThat(processor.process(row("", "100", "2001", "A", "", "", "1", ""))).isEmpty();
    assertThat(processor.process(row("", "100", "2001", "B", "", "", "2", "")))
        .contains(ProductImportProcessor.ITEM_DUPLICATE);
  }

  @Test
  void shouldRequireNameAndPrice_onInsert() {
    when(products.selectOne(any())).thenReturn(null);
    assertThat(processor.process(row("", "100", "2001", "", "", "", "", "")))
        .contains(ProductImportProcessor.NAME_REQUIRED, ProductImportProcessor.PRICE_REQUIRED);
  }
}
