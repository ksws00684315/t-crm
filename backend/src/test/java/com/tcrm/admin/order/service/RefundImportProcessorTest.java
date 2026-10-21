package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportTaskOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefundImportProcessorTest {
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final OrderWriter writer = mock(OrderWriter.class);
  private final OrderImportStateWriter importStates = mock(OrderImportStateWriter.class);
  private final OrderSyncFailureWriter syncFailures = mock(OrderSyncFailureWriter.class);
  private RefundImportProcessor processor;

  @BeforeEach
  void setup() {
    processor =
        new RefundImportProcessor(
            77L, shops, writer, importStates, syncFailures, Instant.parse("2026-10-21T00:00:00Z"));
    when(shops.findEnabledByWeidianShopId("100"))
        .thenReturn(new ShopAccessDTO(10L, "Shop", "100", 1));
    when(writer.find(10L, "5001")).thenReturn(new OrderDO());
    when(writer.saveImportedRefund(anyLong(), anyString(), anyString(), any(RefundDO.class)))
        .thenReturn(true);
  }

  private static List<String> row(String... cells) {
    List<String> r = new ArrayList<>(List.of(cells));
    while (r.size() < RefundImportColumn.values().length) {
      r.add("");
    }
    return r;
  }

  private static List<String> valid() {
    return row("100", "R1", "5001", "sub-1", "1.50", "2026-10-16 13:00:00", "2026-10-16 14:00:00");
  }

  @Test
  void shouldRejectMissingOrder() {
    when(writer.find(10L, "5001")).thenReturn(null);
    assertThat(processor.process(valid()))
        .extracting(ImportReason::key)
        .containsExactly(RefundImportProcessor.ORDER_MISSING);
    verify(writer, never())
        .saveImportedRefund(anyLong(), anyString(), anyString(), any(RefundDO.class));
  }

  @Test
  void shouldRejectExistingRefund() {
    when(writer.findRefund("R1")).thenReturn(new RefundDO());
    assertThat(processor.process(valid()))
        .extracting(ImportReason::key)
        .containsExactly(RefundImportProcessor.REFUND_EXISTS);
  }

  @Test
  void shouldRejectFinishBeforeStart() {
    List<String> cells = valid();
    cells.set(RefundImportColumn.FINISH_TIME.ordinal(), "2026-10-16 12:00:00");
    assertThat(processor.process(cells))
        .extracting(ImportReason::key)
        .containsExactly(RefundImportProcessor.FINISH_BEFORE_START);
  }

  @Test
  void shouldInsertCompletedRefund() {
    assertThat(processor.process(valid())).isEmpty();
    ArgumentCaptor<RefundDO> cap = ArgumentCaptor.forClass(RefundDO.class);
    verify(writer).saveImportedRefund(eq(10L), eq("5001"), eq("sub-1"), cap.capture());
    assertThat(cap.getValue().getWeidianRefundNo()).isEqualTo("R1");
    assertThat(cap.getValue().getAmount()).isEqualByComparingTo("1.50");
  }

  @Test
  void shouldRejectZeroAmount() {
    List<String> cells = valid();
    cells.set(RefundImportColumn.AMOUNT.ordinal(), "0");
    assertThat(processor.process(cells))
        .extracting(ImportReason::key)
        .containsExactly(RefundImportProcessor.AMOUNT_FORMAT);
  }

  @Test
  void shouldAcceptSlashUnpaddedRefundTimes() {
    List<String> cells = valid();
    cells.set(RefundImportColumn.REFUND_TIME.ordinal(), "2026/10/16 13:00");
    cells.set(RefundImportColumn.FINISH_TIME.ordinal(), "2026/10/16 14:05:07");
    assertThat(processor.process(cells)).isEmpty();
    ArgumentCaptor<RefundDO> cap = ArgumentCaptor.forClass(RefundDO.class);
    verify(writer).saveImportedRefund(eq(10L), eq("5001"), eq("sub-1"), cap.capture());
    assertThat(cap.getValue().getRefundTime()).isNotNull();
    assertThat(cap.getValue().getFinishTime()).isNotNull();
  }

  @Test
  void shouldRecordImportCutoffOncePerShop_whenRowsResolveShops() {
    assertThat(processor.process(valid())).isEmpty();
    assertThat(processor.process(valid())).isEmpty();
    verify(importStates, org.mockito.Mockito.times(1))
        .record(10L, "REFUND", 77L, Instant.parse("2026-10-21T00:00:00Z"));
  }

  @Test
  void shouldRecordFailure_whenImportFinishesWithFailures() {
    processor.onFinish(new ImportTaskOutcome(4, 4, 4, ImportTaskOutcome.Status.FAILED));
    verify(syncFailures)
        .record(
            org.mockito.ArgumentMatchers.isNull(),
            eq(com.tcrm.admin.order.model.OrderSyncFailureKind.REFUND_IMPORT),
            org.mockito.ArgumentMatchers.contains("4 of 4"),
            eq(77L));
  }
}
