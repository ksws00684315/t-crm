package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.task.api.ImportReason;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderImportProcessorTest {
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final OrderWriter writer = mock(OrderWriter.class);
  private OrderImportProcessor processor;

  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @BeforeEach
  void setup() {
    processor = new OrderImportProcessor(99L, shops, writer);
    when(shops.findEnabledByWeidianShopId("100"))
        .thenReturn(new ShopAccessDTO(10L, "Shop", "100", 1));
  }

  private static List<String> row(String... cells) {
    List<String> r = new ArrayList<>(List.of(cells));
    while (r.size() < OrderImportColumn.values().length) {
      r.add("");
    }
    return r;
  }

  private static List<String> validPaid() {
    return row(
        "100",
        "5001",
        "",
        "已付款",
        "2026-10-16 12:00:00",
        "2026-10-16 12:01:00",
        "10.00",
        "0",
        "9001",
        "sku1",
        "商品A",
        "红",
        "1",
        "10.00");
  }

  @Test
  void shouldRejectMissingRequiredFields() {
    List<ImportReason> reasons = processor.process(row());
    assertThat(reasons)
        .extracting(ImportReason::key)
        .contains(
            OrderImportProcessor.SHOP_REQUIRED,
            OrderImportProcessor.ORDER_REQUIRED,
            OrderImportProcessor.STATUS_REQUIRED,
            OrderImportProcessor.ORDER_TIME_REQUIRED,
            OrderImportProcessor.PAY_AMOUNT_REQUIRED,
            OrderImportProcessor.ITEM_REQUIRED,
            OrderImportProcessor.NAME_REQUIRED,
            OrderImportProcessor.QUANTITY_REQUIRED,
            OrderImportProcessor.ITEM_PAY_REQUIRED);
    verify(writer, never()).save(any());
  }

  @Test
  void shouldRequirePayTime_whenStatusIsPaid() {
    List<String> cells = validPaid();
    cells.set(OrderImportColumn.PAY_TIME.ordinal(), "");
    assertThat(processor.process(cells))
        .extracting(ImportReason::key)
        .containsExactly(OrderImportProcessor.PAY_TIME_REQUIRED);
  }

  @Test
  void shouldRejectSyncOwnedOrder() {
    OrderDO existing = new OrderDO();
    existing.setSource(1);
    when(writer.find(10L, "5001")).thenReturn(existing);
    assertThat(processor.process(validPaid()))
        .extracting(ImportReason::key)
        .containsExactly(OrderImportProcessor.SYNC_OWNED);
    verify(writer, never()).save(any());
  }

  @Test
  void shouldSaveImportedOrder_withStableSubOrderAndBatchId() {
    when(writer.find(10L, "5001")).thenReturn(null);
    assertThat(processor.process(validPaid())).isEmpty();
    ArgumentCaptor<OrderPayloadDTO> cap = ArgumentCaptor.forClass(OrderPayloadDTO.class);
    verify(writer).save(cap.capture());
    OrderDO order = cap.getValue().order();
    OrderItemDO item = cap.getValue().items().getFirst();
    assertThat(order.getSource()).isEqualTo(2);
    assertThat(order.getImportBatchId()).isEqualTo(99L);
    assertThat(order.getRawStatus()).isEqualTo("IMPORT:PAID");
    assertThat(order.getOrderStatus()).isEqualTo("PAID");
    assertThat(order.getTotalAmount()).isEqualByComparingTo("10.00");
    assertThat(order.getPayAmount()).isEqualByComparingTo("10.00");
    assertThat(order.getBuyerHash()).isNull();
    assertThat(item.getSubOrderId()).startsWith("line-");
    assertThat(item.getSkuId()).isEqualTo("sku1");
    assertThat(item.getQuantity()).isEqualTo(1);
  }

  @Test
  void shouldReuseStableSubOrder_whenSameFileOrderReappears() {
    when(writer.find(eq(10L), eq("5001"))).thenReturn(null);
    assertThat(processor.process(validPaid())).isEmpty();
    assertThat(processor.process(validPaid())).isEmpty();
    ArgumentCaptor<OrderPayloadDTO> cap = ArgumentCaptor.forClass(OrderPayloadDTO.class);
    verify(writer, org.mockito.Mockito.times(2)).save(cap.capture());
    String first = cap.getAllValues().get(0).items().getFirst().getSubOrderId();
    String second = cap.getAllValues().get(1).items().getFirst().getSubOrderId();
    assertThat(first).isNotEqualTo(second);
    // Re-run a fresh processor (same file again) must regenerate the first key.
    OrderImportProcessor again = new OrderImportProcessor(99L, shops, writer);
    assertThat(again.process(validPaid())).isEmpty();
    verify(writer, org.mockito.Mockito.times(3)).save(cap.capture());
    assertThat(cap.getAllValues().get(2).items().getFirst().getSubOrderId()).isEqualTo(first);
  }

  @Test
  void shouldAcceptEnglishStatusAndUpdateExistingImport() {
    OrderDO existing = new OrderDO();
    existing.setSource(2);
    when(writer.find(10L, "5001")).thenReturn(existing);
    List<String> cells = validPaid();
    cells.set(OrderImportColumn.STATUS.ordinal(), "PAID");
    cells.set(OrderImportColumn.SUB_ORDER_NO.ordinal(), "sub-1");
    assertThat(processor.process(cells)).isEmpty();
    ArgumentCaptor<OrderPayloadDTO> cap = ArgumentCaptor.forClass(OrderPayloadDTO.class);
    verify(writer).save(cap.capture());
    assertThat(cap.getValue().items().getFirst().getSubOrderId()).isEqualTo("sub-1");
    assertThat(cap.getValue().order().getImportBatchId()).isEqualTo(99L);
  }

  @Test
  void shouldTreatOtherCompanyShopAsMissing() {
    when(shops.findEnabledByWeidianShopId("100")).thenReturn(null);
    assertThat(processor.process(validPaid()))
        .extracting(ImportReason::key)
        .containsExactly(OrderImportProcessor.SHOP_MISSING);
  }
}
