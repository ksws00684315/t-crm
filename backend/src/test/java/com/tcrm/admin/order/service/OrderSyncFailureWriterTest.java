package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import com.tcrm.admin.order.model.OrderSyncFailureKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderSyncFailureWriterTest {
  private final OrderSyncFailureMapper failures = mock(OrderSyncFailureMapper.class);
  private final OrderSyncFailureWriter writer = new OrderSyncFailureWriter(failures);

  @Test
  void shouldPersistTruncatedSafeError_whenRecording() {
    writer.record(5L, OrderSyncFailureKind.ORDER_SYNC, "x".repeat(600), null);
    var row = ArgumentCaptor.forClass(OrderSyncFailureDO.class);
    verify(failures).insert(row.capture());
    assertThat(row.getValue().getShopId()).isEqualTo(5L);
    assertThat(row.getValue().getKind()).isEqualTo("ORDER_SYNC");
    assertThat(row.getValue().getError()).hasSize(500);
    assertThat(row.getValue().getTaskId()).isNull();
  }

  @Test
  void shouldDefaultBlankError_andSwallowInsertFailures() {
    writer.record(null, OrderSyncFailureKind.ORDER_IMPORT, "  ", 77L);
    var row = ArgumentCaptor.forClass(OrderSyncFailureDO.class);
    verify(failures).insert(row.capture());
    assertThat(row.getValue().getError()).isEqualTo("unknown error");
    assertThat(row.getValue().getShopId()).isNull();
    assertThat(row.getValue().getTaskId()).isEqualTo(77L);

    doThrow(new RuntimeException("db down")).when(failures).insert(any(OrderSyncFailureDO.class));
    writer.record(1L, OrderSyncFailureKind.REFUND_SYNC, "boom", null);
  }
}
