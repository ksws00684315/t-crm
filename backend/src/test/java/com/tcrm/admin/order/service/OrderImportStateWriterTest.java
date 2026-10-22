package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderImportStateWriterTest {
  private final OrderSyncStateMapper states = mock(OrderSyncStateMapper.class);
  private final OrderImportStateWriter writer = new OrderImportStateWriter(states);

  @BeforeAll
  static void metadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderSyncStateDO.class);
  }

  @Test
  void shouldInsertNewState_whenShopHasNoSyncRow() {
    when(states.selectOne(any())).thenReturn(null);
    Instant cutoff = Instant.parse("2026-10-20T12:00:00Z");
    writer.record(5L, "ORDER", 33L, cutoff);
    var inserted = ArgumentCaptor.forClass(OrderSyncStateDO.class);
    verify(states).insert(inserted.capture());
    assertThat(inserted.getValue().getShopId()).isEqualTo(5L);
    assertThat(inserted.getValue().getLastImportKind()).isEqualTo("ORDER");
    assertThat(inserted.getValue().getLastImportCutoffAt()).isEqualTo(cutoff);
    assertThat(inserted.getValue().getLastImportTaskId()).isEqualTo(33L);
  }

  @Test
  void shouldUpdateExistingState_whenShopAlreadySynced() {
    var existing = new OrderSyncStateDO();
    existing.setId(9L);
    existing.setShopId(5L);
    when(states.selectOne(any())).thenReturn(existing);
    Instant cutoff = Instant.parse("2026-10-20T12:00:00Z");
    writer.record(5L, "REFUND", 44L, cutoff);
    verify(states).updateById(existing);
    assertThat(existing.getLastImportKind()).isEqualTo("REFUND");
    assertThat(existing.getLastImportCutoffAt()).isEqualTo(cutoff);
    assertThat(existing.getLastImportTaskId()).isEqualTo(44L);
  }
}
