package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final com.tcrm.admin.order.api.CommissionOrderStateApi commissionStates =
      mock(com.tcrm.admin.order.api.CommissionOrderStateApi.class);
  private final KolAnalyticsService service =
      new KolAnalyticsService(orders, current, commissionStates);

  @BeforeEach
  void setUp() {
    when(current.kol()).thenReturn(kol());
    when(commissionStates.listByKolAndOrderIds(anyLong(), any())).thenReturn(List.of());
    when(commissionStates.listByKol(anyLong())).thenReturn(List.of());
  }

  @Test
  void shouldBucketOrderPayAmountsIncludingFreightByTheRequestedOffset() {
    // 2026-09-30T17:00Z is 2026-10-01 01:00+08; 2026-10-01T16:00Z is 2026-10-02 00:00+08.
    // Task 112: sales are the order pay amounts (goods 90 + freight 10 = 100.50 / 49.50 here),
    // never the creator's line sums; the attribution and pay-time conditions are unchanged.
    when(orders.selectList(any()))
        .thenReturn(
            List.of(
                order(21L, "2026-09-30T17:00:00Z", "100.50"),
                order(22L, "2026-10-01T16:00:00Z", "49.50")));

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
                "2026-10-01", new BigDecimal("100.50"), new BigDecimal("0.00"), 1L),
            new KolAnalyticsOverviewDTO.Day(
                "2026-10-02", new BigDecimal("49.50"), new BigDecimal("0.00"), 1L));
    assertThat(overview.asOf()).isNotNull();

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("kol_id")
        .contains("attribution_status")
        .contains("pay_time");
  }

  @Test
  void shouldIncludeFreightInSales_forOrder81000000002Shape() {
    // Goods 90 + freight 10, paid 100: the workbench counts 100, not the 90 of the goods line.
    when(orders.selectList(any()))
        .thenReturn(List.of(order(21L, "2026-09-30T17:00:00Z", "100.00")));

    var overview = service.overview("2026-10-01T00:00:00+08:00", "2026-10-02T00:00:00+08:00");

    assertThat(overview.salesCny()).isEqualByComparingTo("100.00");
    assertThat(overview.daily().getFirst().salesCny()).isEqualByComparingTo("100.00");
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

  @Test
  void shouldSumCommissionByPayDayAndReportCurrentFrozen() {
    // Two orders in range: commission 3.00 (paid 10-01) and 2.00 (paid 10-02); plus one more
    // line outside the range whose 5.00 accrual (2.00 released) only feeds the frozen total.
    when(orders.selectList(any()))
        .thenReturn(
            List.of(
                order(21L, "2026-09-30T17:00:00Z", "100.50"),
                order(22L, "2026-10-01T16:00:00Z", "49.50")));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    700L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.0300"),
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00")),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    22L,
                    701L,
                    "FROZEN",
                    new BigDecimal("0.0400"),
                    new BigDecimal("2.00"),
                    BigDecimal.ZERO)));
    // Frozen over ALL live rows: (3.00 - 3.00) + (2.00 - 0.00) + (5.00 - 2.00) = 5.00.
    when(commissionStates.listByKol(anyLong()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    700L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.0300"),
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00")),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    22L,
                    701L,
                    "FROZEN",
                    new BigDecimal("0.0400"),
                    new BigDecimal("2.00"),
                    BigDecimal.ZERO),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    99L,
                    800L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.0500"),
                    new BigDecimal("5.00"),
                    new BigDecimal("2.00"))));

    var overview = service.overview("2026-10-01T00:00:00+08:00", "2026-10-03T00:00:00+08:00");

    assertThat(overview.commissionCny()).isEqualByComparingTo("5.00");
    assertThat(overview.withdrawnCny()).isEqualByComparingTo("0.00");
    assertThat(overview.withdrawingCny()).isEqualByComparingTo("0.00");
    assertThat(overview.frozenCny()).isEqualByComparingTo("5.00");
    assertThat(overview.daily().get(0).commissionCny()).isEqualByComparingTo("3.00");
    assertThat(overview.daily().get(1).commissionCny()).isEqualByComparingTo("2.00");
  }

  @Test
  void shouldSkipZeroCommissionOrders_whenBuildingTheDailyAxis() {
    // The order is in range but its line has no accrual yet (unverified): the commission day map
    // skips it, so the daily row keeps "0.00" without adding a day of its own.
    when(orders.selectList(any()))
        .thenReturn(List.of(order(21L, "2026-09-30T17:00:00Z", "100.50")));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any())).thenReturn(List.of());
    when(commissionStates.listByKol(anyLong())).thenReturn(List.of());

    var overview = service.overview("2026-10-01T00:00:00+08:00", "2026-10-02T00:00:00+08:00");

    assertThat(overview.commissionCny()).isEqualByComparingTo("0.00");
    assertThat(overview.frozenCny()).isEqualByComparingTo("0.00");
    assertThat(overview.daily())
        .containsExactly(
            new KolAnalyticsOverviewDTO.Day(
                "2026-10-01", new BigDecimal("100.50"), new BigDecimal("0.00"), 1L));
  }

  @Test
  void shouldTakeTheCumulativeSalesFromTheSqlAggregateOfTheSignedInCreator() {
    when(orders.sumAttributedPayAmount(700L)).thenReturn(new BigDecimal("12345678.9"));

    var total = service.salesTotal();

    assertThat(total.salesCny()).isEqualTo(new BigDecimal("12345678.90"));
    assertThat(total.asOf()).isNotNull();
    verify(orders).sumAttributedPayAmount(700L);
    // The aggregate never reads rows into memory.
    verify(orders, org.mockito.Mockito.never()).selectList(any());
  }

  @Test
  void shouldReturnZeroCumulativeSales_whenThereIsNoOrder() {
    when(orders.sumAttributedPayAmount(700L)).thenReturn(null);

    assertThat(service.salesTotal().salesCny()).isEqualTo(new BigDecimal("0.00"));
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

  private static OrderDO order(long id, String payTime, String payAmount) {
    OrderDO value = new OrderDO();
    value.setId(id);
    value.setKolId(700L);
    value.setAttributionStatus("ATTRIBUTED");
    value.setOrderStatus("PAID");
    value.setPayTime(Instant.parse(payTime));
    value.setPayAmount(new BigDecimal(payAmount));
    return value;
  }
}
