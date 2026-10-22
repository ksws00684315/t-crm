package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.integration.api.WeidianBusinessApi.RefundDetailResponse;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class OrderWriterTest {
  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final RefundMapper refunds = mock(RefundMapper.class);
  private final OrderAttribution attribution = mock(OrderAttribution.class);
  private final OrderWriter writer = new OrderWriter(orders, items, refunds, attribution);

  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class, RefundDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private static OrderDO order() {
    var o = new OrderDO();
    o.setId(10L);
    o.setTenantId(1L);
    o.setShopId(5L);
    o.setWeidianOrderId("100");
    return o;
  }

  private static OrderItemDO item(long id, String sub, String sku) {
    var i = new OrderItemDO();
    i.setId(id);
    i.setOrderId(10L);
    i.setSubOrderId(sub);
    i.setWeidianItemId("90");
    i.setSkuId(sku);
    i.setAttributionType(0);
    return i;
  }

  private static RefundDetailResponse refund(String raw) {
    return new RefundDetailResponse(
        Instant.parse("2026-10-16T00:00:00Z"),
        Instant.parse("2026-10-16T01:00:00Z"),
        "800",
        "100",
        new BigDecimal("125"),
        JsonMapper.builder().build().readTree(raw));
  }

  @Test
  void shouldInsertOrderAndLinkNewItems_whenOrderIsNew() {
    var o = order();
    var i = item(20, "sub", "sku");
    when(items.selectList(any())).thenReturn(List.of(), List.of(i));
    writer.save(new OrderPayloadDTO(o, List.of(i)));
    verify(orders).insert(o);
    verify(items).insert(i);
    verify(attribution).apply(o, List.of(i), false);
    assertThat(i.getOrderId()).isEqualTo(10L);
  }

  @Test
  void shouldPreserveManualDecisionAndSettlement_whenExistingOrderChanges() {
    var existing = order();
    existing.setSettledTime(Instant.parse("2026-10-15T00:00:00Z"));
    var old = item(20, "sub", "old-sku");
    old.setKolId(100L);
    old.setAttributionType(2);
    old.setManualReason("confirmed");
    old.setRefundAmount(new BigDecimal("1.25"));
    var next = order();
    next.setId(null);
    next.setTenantId(null);
    var line = item(99, "sub", "new-sku");
    line.setItemName("Updated product");
    line.setSkuName("Updated size");
    line.setQuantity(2);
    line.setPayAmount(new BigDecimal("9.50"));
    when(orders.selectOne(any())).thenReturn(existing);
    when(items.selectList(any())).thenReturn(List.of(old));

    writer.save(new OrderPayloadDTO(next, List.of(line)));

    assertThat(next.getId()).isEqualTo(10L);
    assertThat(next.getTenantId()).isEqualTo(1L);
    assertThat(next.getSettledTime()).isEqualTo(existing.getSettledTime());
    assertThat(old.getKolId()).isEqualTo(100L);
    assertThat(old.getAttributionType()).isEqualTo(2);
    assertThat(old.getManualReason()).isEqualTo("confirmed");
    assertThat(old.getRefundAmount()).isEqualByComparingTo("1.25");
    assertThat(old.getSkuId()).isEqualTo("new-sku");
    assertThat(old.getQuantity()).isEqualTo(2);
    assertThat(old.getPayAmount()).isEqualByComparingTo("9.50");
    verify(items, never()).insert(any(OrderItemDO.class));
  }

  @Test
  void shouldFindByShopAndRemoteOrder_whenLookingUpExistingOrder() {
    when(orders.selectOne(any())).thenReturn(order());
    assertThat(writer.find(5, "100").getId()).isEqualTo(10L);
  }

  @Test
  void shouldRejectRefund_whenOrderIsMissing() {
    assertThatThrownBy(() -> writer.saveRefund(5, refund("{}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Refund order is missing");
    verify(refunds, never()).insert(any(RefundDO.class));
  }

  @Test
  void shouldRejectRefundIdentityChange_whenExistingRefundBelongsToAnotherOrder() {
    when(orders.selectOne(any())).thenReturn(order());
    var existing = new RefundDO();
    existing.setOrderId(99L);
    when(refunds.selectOne(any())).thenReturn(existing);
    assertThatThrownBy(() -> writer.saveRefund(5, refund("{}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Refund identity conflict");
    verify(refunds, never()).updateById(any(RefundDO.class));
  }

  @Test
  void shouldConvertFenAndSumLinkedCompletedRefunds_whenSubOrderMatches() {
    var o = order();
    var i = item(20, "sub", "s");
    when(orders.selectOne(any())).thenReturn(o);
    when(items.selectList(any())).thenReturn(List.of(i));
    var first = new RefundDO();
    first.setOrderItemId(20L);
    first.setAmount(new BigDecimal("1.25"));
    var second = new RefundDO();
    second.setOrderItemId(20L);
    second.setAmount(new BigDecimal("0.75"));
    var unlinked = new RefundDO();
    unlinked.setAmount(new BigDecimal("10.00"));
    when(refunds.selectList(any())).thenReturn(List.of(first, second, unlinked));

    writer.saveRefund(
        5,
        refund("{\"refundBasicInfo\":{\"status\":10,\"itemList\":[{\"orderDescId\":\"sub\"}]}}"));

    var saved = ArgumentCaptor.forClass(RefundDO.class);
    verify(refunds).insert(saved.capture());
    assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1.25"); // 125 分 / 100
    assertThat(saved.getValue().getOrderItemId()).isEqualTo(20L);
    assertThat(saved.getValue().getRawStatus()).isEqualTo("10");
    assertThat(i.getRefundAmount()).isEqualByComparingTo("2.00"); // 1.25 + 0.75，排除未关联的 10
    assertThat(o.getLastSyncTime()).isNotNull();
    verify(attribution, never()).apply(any(), any(), eq(false));
  }

  @Test
  void shouldMapRefundDictionaries_whenSavingWeidianRefund() {
    when(orders.selectOne(any())).thenReturn(order());
    when(items.selectList(any())).thenReturn(List.of(item(20, "sub", "s")));
    when(refunds.selectList(any())).thenReturn(List.of());

    writer.saveRefund(
        5,
        refund(
            "{\"refundBasicInfo\":{\"status\":10,\"refund_type\":\"2\","
                + "\"itemList\":[{\"orderDescId\":\"sub\"}]}}"));

    var saved = ArgumentCaptor.forClass(RefundDO.class);
    verify(refunds).insert(saved.capture());
    assertThat(saved.getValue().getRefundStatus()).isEqualTo("SUCCESS");
    assertThat(saved.getValue().getRefundType()).isEqualTo("RETURN_REFUND");
  }

  @Test
  void shouldMarkUnrecognizedRefundFieldsAsUnknown_whenSavingWeidianRefund() {
    when(orders.selectOne(any())).thenReturn(order());
    when(items.selectList(any())).thenReturn(List.of());
    when(refunds.selectList(any())).thenReturn(List.of());

    writer.saveRefund(5, refund("{\"refundBasicInfo\":{\"status\":\"weird\"}}"));

    var saved = ArgumentCaptor.forClass(RefundDO.class);
    verify(refunds).insert(saved.capture());
    assertThat(saved.getValue().getRefundStatus()).isEqualTo("UNKNOWN");
    assertThat(saved.getValue().getRefundType()).isEqualTo("UNKNOWN");
    assertThat(saved.getValue().getRawStatus()).isEqualTo("weird");
  }

  @Test
  void shouldFixImportedRefundToSuccess_whenSavingExcelRefund() {
    when(orders.selectOne(any())).thenReturn(order());
    when(items.selectList(any())).thenReturn(List.of(), List.of());
    when(refunds.selectList(any())).thenReturn(List.of());
    var row = new RefundDO();

    assertThat(writer.saveImportedRefund(5, "100", "sub", row)).isTrue();

    assertThat(row.getRefundStatus()).isEqualTo("SUCCESS");
    assertThat(row.getRefundType()).isEqualTo("UNKNOWN");
  }

  @Test
  void shouldUseUniqueCurrentShopSku_whenRefundHasNoMatchingSubOrder() {
    when(orders.selectOne(any())).thenReturn(order());
    var first = item(20, "first", "a");
    var second = item(21, "second", "b");
    when(items.selectList(any())).thenReturn(List.of(first, second));
    when(refunds.selectList(any())).thenReturn(List.of());
    writer.saveRefund(
        5,
        refund(
            "{\"refundBasicInfo\":{\"status\":10,\"itemList\":[{\"curShopItemId\":\"90\",\"curShopSkuId\":\"b\"}]}}"));
    var saved = ArgumentCaptor.forClass(RefundDO.class);
    verify(refunds).insert(saved.capture());
    assertThat(saved.getValue().getOrderItemId()).isEqualTo(21L);
  }

  @Test
  void shouldClearLinkAndTotals_whenRefundUpdateCannotIdentifyOneItem() {
    when(orders.selectOne(any())).thenReturn(order());
    var existing = new RefundDO();
    existing.setId(30L);
    existing.setOrderId(10L);
    existing.setOrderItemId(20L);
    when(refunds.selectOne(any())).thenReturn(existing);
    var first = item(20, "first", "a");
    var second = item(21, "second", "b");
    when(items.selectList(any())).thenReturn(List.of(first, second));
    when(refunds.selectList(any())).thenReturn(List.of());
    writer.saveRefund(
        5, refund("{\"refundBasicInfo\":{\"status\":9,\"itemList\":[{\"itemId\":\"90\"}]}}"));
    verify(refunds).updateById(existing);
    assertThat(existing.getOrderItemId()).isNull();
    assertThat(existing.getRawStatus()).isEqualTo("9");
    assertThat(first.getRefundAmount()).isEqualByComparingTo("0");
    assertThat(second.getRefundAmount()).isEqualByComparingTo("0");
  }

  @Test
  void shouldKeepRefundAtOrderLevel_whenMultipleOrNoItemsAreProvided() {
    when(orders.selectOne(any())).thenReturn(order());
    when(items.selectList(any())).thenReturn(List.of(item(20, "sub", "s")));
    when(refunds.selectList(any())).thenReturn(List.of());
    for (String raw :
        List.of(
            "{\"refundBasicInfo\":{\"status\":10}}",
            "{\"refundBasicInfo\":{\"status\":10,\"itemList\":[]}}",
            "{\"refundBasicInfo\":{\"status\":10,\"itemList\":[{},{}]}}")) {
      writer.saveRefund(5, refund(raw));
    }
    var saved = ArgumentCaptor.forClass(RefundDO.class);
    verify(refunds, org.mockito.Mockito.times(3)).insert(saved.capture());
    assertThat(saved.getAllValues()).allSatisfy(f -> assertThat(f.getOrderItemId()).isNull());
  }

  @Test
  void shouldInsertImportedRefund_andRefreshItemTotals() {
    var existing = order();
    when(orders.selectOne(any())).thenReturn(existing);
    var line = item(20, "sub", "sku");
    line.setRefundAmount(BigDecimal.ZERO);
    when(items.selectList(any())).thenReturn(List.of(line), List.of(line));
    when(refunds.selectList(any()))
        .thenAnswer(
            inv -> {
              var saved = new RefundDO();
              saved.setOrderItemId(20L);
              saved.setAmount(new BigDecimal("1.25"));
              saved.setRawStatus("10");
              return List.of(saved);
            });
    var row = new RefundDO();
    row.setWeidianRefundNo("R-IMP");
    row.setAmount(new BigDecimal("1.25"));
    row.setRefundTime(Instant.parse("2026-10-16T01:00:00Z"));
    row.setFinishTime(Instant.parse("2026-10-16T02:00:00Z"));
    assertThat(writer.saveImportedRefund(5L, "100", "sub", row)).isTrue();
    assertThat(row.getRawStatus()).isEqualTo("10");
    assertThat(row.getOrderId()).isEqualTo(10L);
    assertThat(row.getOrderItemId()).isEqualTo(20L);
    verify(refunds).insert(row);
    assertThat(line.getRefundAmount()).isEqualByComparingTo("1.25");
  }

  @Test
  void shouldReturnFalse_whenImportedRefundOrderMissing() {
    when(orders.selectOne(any())).thenReturn(null);
    assertThat(writer.saveImportedRefund(5L, "missing", "", new RefundDO())).isFalse();
    verify(refunds, never()).insert(any(RefundDO.class));
  }

  @Test
  void shouldDeleteImportOnlyLines_whenApiSyncReplacesSource2() {
    var existing = order();
    existing.setSource(2);
    var importLine = item(20, "line-imported", "old");
    importLine.setPayAmount(new BigDecimal("10.00"));
    var apiLine = item(99, "wd-sub-1", "sku");
    apiLine.setWeidianItemId("90");
    apiLine.setItemName("API item");
    apiLine.setSkuName("");
    apiLine.setQuantity(1);
    apiLine.setPayAmount(new BigDecimal("10.00"));
    apiLine.setRefundAmount(BigDecimal.ZERO);
    var next = order();
    next.setId(null);
    next.setTenantId(null);
    next.setSource(1);
    next.setPayAmount(new BigDecimal("10.00"));
    when(orders.selectOne(any())).thenReturn(existing);
    // previous items, then remaining after delete
    when(items.selectList(any())).thenReturn(List.of(importLine), List.of(apiLine));
    var linkedRefund = new RefundDO();
    linkedRefund.setId(70L);
    linkedRefund.setOrderItemId(20L);
    when(refunds.selectList(any())).thenReturn(List.of(linkedRefund));

    writer.save(new OrderPayloadDTO(next, List.of(apiLine)));

    verify(items).insert(apiLine);
    verify(items).deleteById(20L);
    assertThat(linkedRefund.getOrderItemId()).isNull();
    verify(refunds).updateById(linkedRefund);
    verify(attribution).apply(eq(next), eq(List.of(apiLine)), eq(false));
  }

  @Test
  void shouldKeepImportLines_whenSavingAnotherImportSource2() {
    var existing = order();
    existing.setSource(2);
    var old = item(20, "line-a", "sku");
    var nextLine = item(99, "line-b", "sku");
    nextLine.setWeidianItemId("91");
    nextLine.setItemName("B");
    nextLine.setSkuName("");
    nextLine.setQuantity(1);
    nextLine.setPayAmount(new BigDecimal("5.00"));
    nextLine.setRefundAmount(BigDecimal.ZERO);
    var next = order();
    next.setId(null);
    next.setSource(2);
    when(orders.selectOne(any())).thenReturn(existing);
    when(items.selectList(any())).thenReturn(List.of(old), List.of(old, nextLine));

    writer.save(new OrderPayloadDTO(next, List.of(nextLine)));

    verify(items).insert(nextLine);
    verify(items, never()).deleteById(anyLong());
  }
}
