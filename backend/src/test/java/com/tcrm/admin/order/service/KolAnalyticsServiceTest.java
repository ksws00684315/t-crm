package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolAnalyticsServiceTest {
  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final KolAnalyticsService service = new KolAnalyticsService(orders, items, current);

  @BeforeEach
  void setUp() {
    when(current.kol()).thenReturn(kol());
  }

  @Test
  void shouldBucketSalesByTheRequestedOffset_andSumOwnLinesOnly() {
    // 2026-09-30T17:00Z is 2026-10-01 01:00+08; 2026-10-01T16:00Z is 2026-10-02 00:00+08.
    // The mapper answers only the creator's own lines; the kol_id constraint is asserted below.
    when(orders.selectList(any()))
        .thenReturn(
            List.of(order(21L, "2026-09-30T17:00:00Z"), order(22L, "2026-10-01T16:00:00Z")));
    when(items.selectList(any()))
        .thenReturn(List.of(line(21L, 700L, "100.50"), line(22L, 700L, "49.50")));

    var overview = service.overview("2026-10-01T00:00:00+08:00", "2026-10-03T00:00:00+08:00");

    assertThat(overview.salesCny()).isEqualByComparingTo("150.00");
    assertThat(overview.paidOrderCount()).isEqualTo(2);
    assertThat(overview.commissionCny()).isEqualByComparingTo("0.00");
    assertThat(overview.withdrawnCny()).isEqualByComparingTo("0.00");
    assertThat(overview.withdrawingCny()).isEqualByComparingTo("0.00");
    assertThat(overview.frozenCny()).isEqualByComparingTo("0.00");
    assertThat(overview.daily())
        .containsExactly(
            new KolAnalyticsOverviewDTO.Day(
                "2026-10-01", new BigDecimal("100.50"), new BigDecimal("0.00")),
            new KolAnalyticsOverviewDTO.Day(
                "2026-10-02", new BigDecimal("49.50"), new BigDecimal("0.00")));
    assertThat(overview.asOf()).isNotNull();

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("kol_id")
        .contains("attribution_status")
        .contains("pay_time");
    var lines = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).selectList(lines.capture());
    assertThat(lines.getValue().getSqlSegment()).contains("kol_id");
  }

  @Test
  void shouldReturnZeroes_whenNoOrdersFallInTheRange() {
    when(orders.selectList(any())).thenReturn(List.of());

    var overview = service.overview("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z");

    assertThat(overview.salesCny()).isEqualByComparingTo("0.00");
    assertThat(overview.paidOrderCount()).isZero();
    assertThat(overview.daily()).isEmpty();
  }

  @Test
  void shouldRejectUnparsableOrInvertedOrOverlongRanges() {
    List<Runnable> invalid =
        List.of(
            () -> service.overview(null, "2026-10-02T00:00:00Z"),
            () -> service.overview("2026-10-01T00:00:00Z", null),
            () -> service.overview("not-a-time", "2026-10-02T00:00:00Z"),
            () -> service.overview("2026-10-01", "2026-10-02T00:00:00Z"),
            () -> service.overview("2026-10-02T00:00:00Z", "2026-10-01T00:00:00Z"),
            () -> service.overview("2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z"),
            () -> service.overview("2024-01-01T00:00:00Z", "2026-10-02T00:00:00Z"));
    for (Runnable run : invalid) {
      assertThatThrownBy(run::run).isInstanceOf(BusinessException.class);
    }
  }

  private static KolAccountDTO kol() {
    return new KolAccountDTO(
        700L,
        "KOL0700",
        "Creator",
        "creator@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        77L,
        "Company",
        true);
  }

  private static OrderDO order(long id, String payTime) {
    OrderDO value = new OrderDO();
    value.setId(id);
    value.setKolId(700L);
    value.setAttributionStatus("ATTRIBUTED");
    value.setOrderStatus("PAID");
    value.setPayTime(Instant.parse(payTime));
    return value;
  }

  private static OrderItemDO line(long orderId, long kolId, String pay) {
    OrderItemDO value = new OrderItemDO();
    value.setId(orderId * 10);
    value.setOrderId(orderId);
    value.setKolId(kolId);
    value.setItemName("Widget");
    value.setPayAmount(new BigDecimal(pay));
    return value;
  }
}
