package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolOwnershipDTO;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.system.api.EnabledTenantApi;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class OrderSnapshotCorrectionTest {
  @BeforeAll
  static void metadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderDO.class);
  }

  private final KolOwnershipApi history = mock(KolOwnershipApi.class);
  private final OrderMapper orders = mock(OrderMapper.class);
  private final EnabledTenantApi tenants = mock(EnabledTenantApi.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private OrderSnapshotCorrection service() {
    when(redis.opsForValue()).thenReturn(values);
    when(tenants.enabledTenantIds()).thenReturn(List.of(1L));
    return new OrderSnapshotCorrection(history, orders, tenants, locks, redis);
  }

  @Test
  void shouldApplyNewIntervalsAndSaveCheckpoint_whenAllCompaniesSucceed() {
    var service = service();
    when(values.get(OrderSnapshotCorrection.CHECKPOINT))
        .thenReturn(Instant.now().minusSeconds(3600).toString());
    when(history.createdBetween(any(), any()))
        .thenReturn(
            List.of(
                new KolOwnershipDTO(100L, 8L, Instant.now().minusSeconds(100), null),
                new KolOwnershipDTO(
                    100L, null, Instant.now().minusSeconds(200), Instant.now().minusSeconds(100))));
    service.correct();
    verify(orders, org.mockito.Mockito.times(2))
        .update(
            org.mockito.ArgumentMatchers.isNull(),
            any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    verify(values)
        .set(org.mockito.ArgumentMatchers.eq(OrderSnapshotCorrection.CHECKPOINT), anyString());
  }

  @Test
  void shouldKeepCheckpoint_whenAnyCompanyFails() {
    var service = service();
    when(history.createdBetween(any(), any())).thenThrow(new IllegalStateException("test"));
    assertThatThrownBy(service::correct).isInstanceOf(IllegalStateException.class);
    verify(values, never()).set(anyString(), anyString());
  }

  @Test
  void shouldUseLockAndRespectLifecycle_whenScheduled() throws Exception {
    var service = service();
    service.setScheduled(false);
    service.scheduled();
    verify(locks, never()).run(anyString(), anyLong(), anyLong(), any());
    service.setScheduled(true);
    service.scheduled();
    verify(locks)
        .run(
            org.mockito.ArgumentMatchers.eq("order:bd-snapshot"),
            org.mockito.ArgumentMatchers.eq(0L),
            org.mockito.ArgumentMatchers.eq(-1L),
            any());
  }
}
