package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

class KolOrderServiceTest {
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
  private final com.tcrm.admin.order.api.CommissionOrderStateApi commissionStates =
      mock(com.tcrm.admin.order.api.CommissionOrderStateApi.class);
  private final KolOrderService service =
      new KolOrderService(orders, items, current, commissionStates);

  @BeforeEach
  void setUp() {
    when(current.kol()).thenReturn(kol());
    when(items.selectList(any())).thenReturn(List.of(item(21L, 700L, "25.00", "5.00")));
    when(commissionStates.listByOrderItemIds(anyLong(), any())).thenReturn(List.of());
  }

  @Test
  void shouldMarkTheOrderAfterSalesWhenAnyOwnLineIs() {
    // Task 113: the portal shows the after-sales hint from the order-level flag.
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    order.setPayAmount(new BigDecimal("30.00"));
    when(orders.selectPage(any(), any())).thenReturn(page(order));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    com.tcrm.admin.order.api.CommissionOrderStateApi.STATUS_FROZEN,
                    new BigDecimal("0.1000"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"),
                    true)));

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20));

    assertThat(result.items().getFirst().afterSales()).isTrue();
    assertThat(result.items().getFirst().items().getFirst().commissionAmount())
        .isEqualByComparingTo("0.00");
  }

  @Test
  void shouldReturnMaskedOrderWithOrderPayAmountIncludingFreight() {
    // Task 112: the order amount is the order pay amount (goods 25 + freight 5 = 30), not the
    // sum of the creator's lines.
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    order.setPayAmount(new BigDecimal("30.00"));
    Page<OrderDO> page = page(order);
    when(orders.selectPage(any(), any())).thenReturn(page);

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20));

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items())
        .containsExactly(
            new KolOrderDTO(
                "1234****9012",
                order.getPayTime(),
                "PAID",
                new BigDecimal("30.00"),
                new BigDecimal("5.00"),
                // No commission snapshot yet: the line and the order are INVALID.
                com.tcrm.admin.order.api.CommissionOrderStateApi.STATUS_INVALID,
                List.of(
                    new KolOrderDTO.Line(
                        "Widget",
                        "Blue",
                        2,
                        new BigDecimal("25.00"),
                        new BigDecimal("5.00"),
                        com.tcrm.admin.order.api.CommissionOrderStateApi.STATUS_INVALID,
                        null,
                        null)),
                false));
    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("kol_id")
        .contains("attribution_status")
        .contains("pay_time");
  }

  @Test
  void shouldKeepOnlyLastTwoCharacters_whenOrderNumberIsEightCharactersOrShorter() {
    assertThat(KolOrderService.maskOrderNo("12345678")).isEqualTo("78");
    assertThat(KolOrderService.maskOrderNo("1234")).isEqualTo("34");
  }

  @Test
  void shouldRejectSizeAboveFifty() {
    assertThatThrownBy(
            () -> service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 51)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldRejectInvalidDateRange() {
    assertThatThrownBy(
            () ->
                service.page(
                    new KolOrderFilterDTO(
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null,
                        null,
                        null,
                        null,
                        1,
                        20)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldRejectUnknownStatusAndOutOfRangePage() {
    assertThatThrownBy(
            () ->
                service.page(new KolOrderFilterDTO(null, null, "SETTLED", null, null, null, 1, 20)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 0, 20)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 0)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldFilterByOrderNumberAndOwnProductName() {
    when(orders.selectPage(any(), any()))
        .thenReturn(page(order(21L, "123456789012", "ATTRIBUTED", "PAID")));

    service.page(new KolOrderFilterDTO(null, null, null, "  1234  ", "Widget", null, 1, 20));

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectPage(any(), query.capture());
    String sql = query.getValue().getSqlSegment();
    assertThat(sql).contains("weidian_order_id ILIKE");
    assertThat(sql).contains("crm_order_item");
    assertThat(sql).contains("oi.kol_id");
    assertThat(sql).contains("oi.item_name ILIKE");
    assertThat(params(query.getValue()).values()).contains("%1234%", "%Widget%", 700L);
  }

  @Test
  void shouldIgnoreBlankKeywordFilters() {
    when(orders.selectPage(any(), any())).thenReturn(page());

    service.page(new KolOrderFilterDTO(null, null, null, "   ", "", null, 1, 20));

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).doesNotContain("ILIKE");
  }

  @Test
  void shouldRejectKeywordsLongerThanOneHundredTwentyEight() {
    String keyword = "x".repeat(129);
    assertThatThrownBy(
            () -> service.page(new KolOrderFilterDTO(null, null, null, keyword, null, null, 1, 20)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.page(new KolOrderFilterDTO(null, null, null, null, keyword, null, 1, 20)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldEscapeLikeWildcards_inKeywordFilters() {
    when(orders.selectPage(any(), any()))
        .thenReturn(page(order(21L, "123456789012", "ATTRIBUTED", "PAID")));

    service.page(new KolOrderFilterDTO(null, null, null, "50%_1\\", null, null, 1, 20));

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("ESCAPE");
    assertThat(params(query.getValue()).values()).contains("%50\\%\\_1\\\\%");
  }

  @Test
  void shouldReturnAnEmptyPageWithoutReadingLines_whenNothingMatches() {
    when(orders.selectPage(any(), any())).thenReturn(page());

    var result =
        service.page(
            new KolOrderFilterDTO(
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-02T00:00:00Z"),
                "PAID",
                null,
                null,
                null,
                2,
                10));

    assertThat(result.items()).isEmpty();
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(10);
    verifyNoInteractions(items);
  }

  @Test
  void shouldReportZeroAmounts_whenLineAmountsAreMissing() {
    when(orders.selectPage(any(), any()))
        .thenReturn(page(order(21L, "123456789012", "ATTRIBUTED", "PAID")));
    OrderItemDO line = item(21L, 700L, "1.00", "0.00");
    line.setPayAmount(null);
    line.setRefundAmount(null);
    when(items.selectList(any())).thenReturn(List.of(line));

    var row =
        service
            .page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20))
            .items()
            .getFirst();

    assertThat(row.payAmount()).isEqualByComparingTo("0.00");
    assertThat(row.refundAmount()).isEqualByComparingTo("0.00");
    assertThat(row.items().getFirst().payAmount()).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void shouldMaskMissingOrderNumbersAsEmpty() {
    assertThat(KolOrderService.maskOrderNo(null)).isEmpty();
    assertThat(KolOrderService.maskOrderNo(" ")).isEmpty();
    assertThat(KolOrderService.maskOrderNo("1")).isEqualTo("1");
  }

  @Test
  void shouldEnrichLinesWithCommissionStateAndLadder() {
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    when(orders.selectPage(any(), any())).thenReturn(page(order));
    // The item fixture has id 21 (item(21L, kolId, ...)); the port keys by order item id.
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.1000"),
                    new BigDecimal("2.50"),
                    new BigDecimal("2.50"))));

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20));

    assertThat(result.items().getFirst().settlementStatus()).isEqualTo("WITHDRAWABLE");
    var line = result.items().getFirst().items().getFirst();
    assertThat(line.settlementStatus()).isEqualTo("WITHDRAWABLE");
    assertThat(line.rateSnapshot()).isEqualByComparingTo("0.1000");
    assertThat(line.commissionAmount()).isEqualByComparingTo("2.50");
  }

  @Test
  void shouldTakeMostActiveLineAsOrderStatus() {
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    when(orders.selectPage(any(), any())).thenReturn(page(order));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                // A frozen line outranks nothing; WITHDRAWABLE outranks FROZEN in the ladder.
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    22L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));
    when(items.selectList(any()))
        .thenReturn(List.of(item(21L, 700L, "25.00", "5.00"), item(22L, 700L, "10.00", "0.00")));

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20));

    assertThat(result.items().getFirst().settlementStatus()).isEqualTo("FROZEN");
  }

  @Test
  void shouldFilterBySettlementStatusViaCandidateResolution() {
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    when(orders.selectList(any())).thenReturn(List.of(order));
    when(orders.selectPage(any(), any())).thenReturn(page(order));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));
    // The enriched page stage resolves the same line through the item-id port.
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, "FROZEN", 1, 20));

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items().getFirst().settlementStatus()).isEqualTo("FROZEN");
    // Filter path resolves candidates (selectList) then pages the matched ids (selectList again).
    verify(orders, org.mockito.Mockito.times(2)).selectList(any());
  }

  @Test
  void shouldCombineSettlementStatusWithOrderNumberFilter() {
    OrderDO matching = order(21L, "123432597890", "ATTRIBUTED", "PAID");
    OrderDO other = order(31L, "999900001111", "ATTRIBUTED", "PAID");
    // Candidates resolve through the port; the orderNo filter must apply to the candidate query.
    when(orders.selectList(any())).thenReturn(List.of(matching), List.of(matching));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    31L,
                    31L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));
    when(items.selectList(any())).thenReturn(List.of(item(21L, 700L, "10.00", "0.00")));

    var result =
        service.page(new KolOrderFilterDTO(null, null, null, "3259", null, "FROZEN", 1, 20));

    // Both orders are FROZEN, but only the one matching orderNo=3259 is returned.
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.items().getFirst().orderNo()).isEqualTo("1234****7890");
    var candidateQuery = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders, org.mockito.Mockito.times(2)).selectList(candidateQuery.capture());
    assertThat(candidateQuery.getAllValues().get(0).getSqlSegment()).contains("weidian_order_id");
  }

  @Test
  void shouldCombineSettlementStatusWithProductNameFilter() {
    OrderDO matching = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    OrderDO other = order(31L, "999900001111", "ATTRIBUTED", "PAID");
    when(orders.selectList(any())).thenReturn(List.of(matching), List.of(matching));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.1000"),
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00")),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    31L,
                    31L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.1000"),
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00"))));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.1000"),
                    new BigDecimal("3.00"),
                    new BigDecimal("3.00"))));
    when(items.selectList(any())).thenReturn(List.of(item(21L, 700L, "10.00", "0.00")));

    var result =
        service.page(
            new KolOrderFilterDTO(null, null, null, null, "Widget", "WITHDRAWABLE", 1, 20));

    assertThat(result.total()).isEqualTo(1);
    var candidateQuery = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders, org.mockito.Mockito.times(2)).selectList(candidateQuery.capture());
    assertThat(candidateQuery.getAllValues().get(0).getSqlSegment()).contains("crm_order_item");
  }

  @Test
  void shouldReturnEmptyPage_whenNoOrderMatchesTheSettlementStatus() {
    when(orders.selectList(any()))
        .thenReturn(List.of(order(21L, "123456789012", "ATTRIBUTED", "PAID")));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(
            List.of(
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));

    // Asking for WITHDRAWABLE when the only line is FROZEN: an empty page, no item queries.
    var result =
        service.page(new KolOrderFilterDTO(null, null, null, null, null, "WITHDRAWABLE", 1, 20));

    assertThat(result.total()).isZero();
    assertThat(result.items()).isEmpty();
    verify(items, never()).selectList(any());
    verify(commissionStates, never()).listByOrderItemIds(anyLong(), any());
  }

  @Test
  void shouldTakeWithdrawableOverFrozenInTheLadder() {
    OrderDO order = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    when(orders.selectPage(any(), any())).thenReturn(page(order));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                // Line 21 released (WITHDRAWABLE outranks FROZEN of line 22)…
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    21L,
                    "WITHDRAWABLE",
                    new BigDecimal("0.1000"),
                    new BigDecimal("2.00"),
                    new BigDecimal("2.00")),
                new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
                    21L,
                    22L,
                    "FROZEN",
                    new BigDecimal("0.1000"),
                    new BigDecimal("1.00"),
                    BigDecimal.ZERO)));
    when(items.selectList(any()))
        .thenReturn(List.of(item(21L, 700L, "25.00", "5.00"), item(22L, 700L, "10.00", "0.00")));

    var result = service.page(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 20));

    assertThat(result.items().getFirst().settlementStatus()).isEqualTo("WITHDRAWABLE");
  }

  @Test
  void shouldRejectUnknownSettlementStatus() {
    assertThatThrownBy(
            () ->
                service.page(new KolOrderFilterDTO(null, null, null, null, null, "MYSTERY", 1, 20)))
        .isInstanceOf(BusinessException.class);
  }

  private static com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO state(
      long orderId, long itemId, String status) {
    return new com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO(
        orderId, itemId, status, new BigDecimal("0.1000"), BigDecimal.ONE, BigDecimal.ZERO);
  }

  private static OrderItemDO summaryLine(long id, String pay, String refund) {
    OrderItemDO value = item(id, 700L, pay, refund);
    value.setOrderId(21L);
    return value;
  }

  @Test
  void shouldSummariseSalesSettledPendingAndRefundedByLineStatus() {
    when(orders.selectList(any()))
        .thenReturn(List.of(order(21L, "123456789012", "ATTRIBUTED", "PAID")));
    when(items.selectList(any()))
        .thenReturn(
            List.of(
                // Settled in three flavours: pay 100 refund 20 -> 80, 50 -> 50, 30 refund 30 -> 0.
                summaryLine(1L, "100.00", "20.00"),
                summaryLine(2L, "50.00", "0.00"),
                summaryLine(3L, "30.00", "30.00"),
                // Pending (frozen) 40 refund 10 -> 30.
                summaryLine(4L, "40.00", "10.00"),
                // Unverified (no snapshot) and no-commission lines count in sales only.
                summaryLine(5L, "7.00", "0.00"),
                summaryLine(6L, "8.00", "8.00")));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(
            List.of(
                state(21L, 1L, "WITHDRAWABLE"),
                state(21L, 2L, "WITHDRAWING"),
                state(21L, 3L, "WITHDRAWN"),
                state(21L, 4L, "FROZEN"),
                state(21L, 6L, "NO_COMMISSION")));

    var summary = service.summary(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 1));

    assertThat(summary.salesCny()).isEqualTo(new BigDecimal("235.00"));
    assertThat(summary.settledCny()).isEqualTo(new BigDecimal("130.00"));
    assertThat(summary.pendingCny()).isEqualTo(new BigDecimal("30.00"));
    assertThat(summary.refundedCny()).isEqualTo(new BigDecimal("68.00"));
    // Verify the order query is the list's base query restricted to paid orders.
    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("kol_id")
        .contains("attribution_status")
        .contains("pay_time IS NOT NULL");
    var lines = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).selectList(lines.capture());
    assertThat(lines.getValue().getSqlSegment()).contains("kol_id");
  }

  @Test
  void shouldReturnZeroSummaryWithoutReadingLines_whenNoOrderMatches() {
    when(orders.selectList(any())).thenReturn(List.of());

    var summary = service.summary(new KolOrderFilterDTO(null, null, null, null, null, null, 1, 1));

    assertThat(summary.salesCny()).isEqualTo(new BigDecimal("0.00"));
    assertThat(summary.settledCny()).isEqualTo(new BigDecimal("0.00"));
    assertThat(summary.pendingCny()).isEqualTo(new BigDecimal("0.00"));
    assertThat(summary.refundedCny()).isEqualTo(new BigDecimal("0.00"));
    verifyNoInteractions(items);
  }

  @Test
  void shouldApplyTheSameFiltersToTheSummaryAsToTheList() {
    when(orders.selectList(any())).thenReturn(List.of());

    service.summary(
        new KolOrderFilterDTO(
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"),
            "PAID",
            "1234",
            "Widget",
            null,
            1,
            1));

    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(orders).selectList(query.capture());
    String sql = query.getValue().getSqlSegment();
    assertThat(sql)
        .contains("pay_time")
        .contains("order_status")
        .contains("weidian_order_id ILIKE")
        .contains("oi.item_name ILIKE")
        .contains("oi.kol_id");
    assertThat(params(query.getValue()).values()).contains("%1234%", "%Widget%", 700L, "PAID");
  }

  @Test
  void shouldSummariseOnlyOrdersWhoseStatusMatchesTheSettlementFilter() {
    OrderDO frozen = order(21L, "123456789012", "ATTRIBUTED", "PAID");
    OrderDO released = order(31L, "999900001111", "ATTRIBUTED", "PAID");
    when(orders.selectList(any())).thenReturn(List.of(frozen, released));
    when(commissionStates.listByKolAndOrderIds(anyLong(), any()))
        .thenReturn(List.of(state(21L, 21L, "FROZEN"), state(31L, 31L, "WITHDRAWABLE")));
    when(items.selectList(any())).thenReturn(List.of(item(21L, 700L, "10.00", "0.00")));
    when(commissionStates.listByOrderItemIds(anyLong(), any()))
        .thenReturn(List.of(state(21L, 21L, "FROZEN")));

    var summary =
        service.summary(new KolOrderFilterDTO(null, null, null, null, null, "FROZEN", 1, 1));

    assertThat(summary.pendingCny()).isEqualTo(new BigDecimal("10.00"));
    // Only the FROZEN order's id reaches the line query.
    var lines = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).selectList(lines.capture());
    assertThat(lines.getValue().getSqlSegment()).contains("order_id");
    assertThat(params(lines.getValue()).values()).contains(21L).doesNotContain(31L);
  }

  @Test
  void shouldRejectInvalidSummaryFilters() {
    assertThatThrownBy(
            () ->
                service.summary(
                    new KolOrderFilterDTO(null, null, null, null, null, "MYSTERY", 1, 1)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.summary(
                    new KolOrderFilterDTO(
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null,
                        null,
                        null,
                        null,
                        1,
                        1)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(orders);
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

  private static OrderDO order(long id, String orderNo, String attribution, String status) {
    OrderDO value = new OrderDO();
    value.setId(id);
    value.setKolId(700L);
    value.setWeidianOrderId(orderNo);
    value.setAttributionStatus(attribution);
    value.setOrderStatus(status);
    value.setPayTime(Instant.parse("2026-10-15T12:00:00Z"));
    return value;
  }

  private static OrderItemDO item(long id, long kolId, String pay, String refund) {
    OrderItemDO value = new OrderItemDO();
    value.setId(id);
    value.setOrderId(21L);
    value.setKolId(kolId);
    value.setItemName("Widget");
    value.setSkuName("Blue");
    value.setQuantity(2);
    value.setPayAmount(new BigDecimal(pay));
    value.setRefundAmount(new BigDecimal(refund));
    return value;
  }

  private static Page<OrderDO> page(OrderDO... rows) {
    Page<OrderDO> page = new Page<>(1, 20);
    page.setRecords(List.of(rows));
    page.setTotal(rows.length);
    return page;
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> params(Wrapper<OrderDO> wrapper) {
    return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<Object, Object, ?>)
            (Object) wrapper)
        .getParamNameValuePairs();
  }
}
