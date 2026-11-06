package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.CommissionOrderStateApi;
import com.tcrm.admin.order.api.CommissionOrderStateApi.ItemCommissionStateDTO;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** Refund list and detail queries (task 40 §3.3). */
class RefundQueryServiceTest {
  private static final Instant PAID = Instant.parse("2026-10-16T00:00:00Z");
  private final RefundMapper refunds = mock(RefundMapper.class);
  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final KolApi kols = mock(KolApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final AccountDTO account = mock(AccountDTO.class);
  private final CommissionOrderStateApi commissionStates = mock(CommissionOrderStateApi.class);
  private final RefundQueryService service =
      new RefundQueryService(
          refunds, orders, items, shops, kols, products, current, commissionStates);

  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class, RefundDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @BeforeEach
  void setup() {
    when(current.user()).thenReturn(account);
    when(account.ownScope()).thenReturn(false);
    when(shops.listShops())
        .thenReturn(List.of(new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005")));
    when(kols.listByIds(any())).thenReturn(List.of());
  }

  private static RefundDO refund(long id, long orderId, Long itemId) {
    var row = new RefundDO();
    row.setId(id);
    row.setOrderId(orderId);
    row.setOrderItemId(itemId);
    row.setWeidianRefundNo("R" + id);
    row.setRefundStatus("SUCCESS");
    row.setRefundType("RETURN_REFUND");
    row.setAmount(new BigDecimal("1.25"));
    row.setRefundTime(PAID);
    row.setFinishTime(PAID.plusSeconds(60));
    return row;
  }

  private static OrderDO order(long id, Long kolId, Long bdUserId) {
    var o = new OrderDO();
    o.setId(id);
    o.setShopId(5L);
    o.setWeidianOrderId("100" + id);
    o.setKolId(kolId);
    o.setBdUserId(bdUserId);
    return o;
  }

  @Test
  void shouldSelectNothing_whenChannelIsUnknown() {
    assertThat(service.page(new RefundFilterDTO(null, null, "TMALL", null, null, 1, 20)).items())
        .isEmpty();
    verify(refunds, org.mockito.Mockito.never()).selectPage(any(), any());
  }

  @Test
  void shouldReturnEmptyPage_whenOrderNumberMatchesNothing() {
    when(orders.selectList(any())).thenReturn(List.of());
    assertThat(service.page(new RefundFilterDTO(null, "404", null, null, null, 1, 20)).items())
        .isEmpty();
    verify(refunds, org.mockito.Mockito.never()).selectPage(any(), any());
  }

  @Test
  void shouldApplyExactNumberAndStatusFilters_whenPaging() {
    when(refunds.selectPage(any(), any())).thenReturn(new Page<RefundDO>(1, 20, 0));
    service.page(new RefundFilterDTO("R7", null, "WEIDIAN", "SUCCESS", "REFUND_ONLY", 2, 10));
    var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(refunds).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("weidian_refund_no", "refund_status", "refund_type");
    assertThat(query.getValue().getParamNameValuePairs().values())
        .contains("R7", "SUCCESS", "REFUND_ONLY");
  }

  @Test
  void shouldHideForeignBdScope_whenAccountIsOwnScoped() {
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(7L);
    when(refunds.selectPage(any(), any())).thenReturn(new Page<RefundDO>(1, 20, 0));
    service.page(new RefundFilterDTO(null, null, null, null, null, 1, 20));
    var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(refunds).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("EXISTS");
    assertThat(query.getValue().getSqlSegment()).contains("bd_user_id");
  }

  @Test
  void shouldDecorateRowsWithOrderShopAndLineKol_whenPaging() {
    when(refunds.selectPage(any(), any()))
        .thenAnswer(
            call -> {
              Page<RefundDO> page = call.getArgument(0);
              page.setRecords(List.of(refund(1, 10, 20L)));
              page.setTotal(1);
              return page;
            });
    when(orders.selectByIds(any())).thenReturn(List.of(order(10, 100L, 7L)));
    var line = new OrderItemDO();
    line.setId(20L);
    line.setKolId(200L);
    when(items.selectByIds(any())).thenReturn(List.of(line));
    when(kols.listByIds(any()))
        .thenReturn(
            List.of(
                new KolRefDTO(100L, "KOL0100", "Order kol", true, null),
                new KolRefDTO(200L, "KOL0200", "Line kol", true, null)));

    PageResult<RefundDTO> result =
        service.page(new RefundFilterDTO(null, null, null, null, null, 1, 20));

    var row = result.items().getFirst();
    assertThat(row.orderNo()).isEqualTo("10010");
    assertThat(row.shopName()).isEqualTo("Main shop");
    assertThat(row.shopCode()).isEqualTo("S005");
    // The line's KOL wins over the order's KOL.
    assertThat(row.kolCode()).isEqualTo("KOL0200");
    assertThat(row.refundType()).isEqualTo("RETURN_REFUND");
    assertThat(row.applyTime()).isEqualTo(PAID);
    assertThat(row.finishTime()).isEqualTo(PAID.plusSeconds(60));
  }

  @Test
  void shouldReturnDetailWithLinkedLine_whenRefundExists() {
    when(refunds.selectById(1L)).thenReturn(refund(1, 10, 20L));
    when(orders.selectById(10L)).thenReturn(order(10, 100L, null));
    when(shops.findShop(5L)).thenReturn(new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005"));
    var line = new OrderItemDO();
    line.setId(20L);
    line.setOrderId(10L);
    line.setKolId(100L);
    line.setProductId(300L);
    line.setItemName("Product");
    line.setWeidianItemId("90");
    line.setSkuName("Size");
    line.setQuantity(2);
    line.setRefundAmount(new BigDecimal("1.25"));
    when(items.selectById(20L)).thenReturn(line);
    when(products.getProduct(300L))
        .thenReturn(new ProductAccessApi.ProductRefDTO(300L, "P1", "Product", "https://img", 1));
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, null)));

    var detail = service.detail(1L);

    assertThat(detail.merchantNo()).isEqualTo("wd-5");
    assertThat(detail.shopCode()).isEqualTo("S005");
    assertThat(detail.refundStatus()).isEqualTo("SUCCESS");
    assertThat(detail.lines()).hasSize(1);
    var first = detail.lines().getFirst();
    assertThat(first.imageUrl()).isEqualTo("https://img");
    assertThat(first.kolCode()).isEqualTo("KOL0100");
    assertThat(first.refundAmount()).isEqualByComparingTo("1.25");
  }

  @Test
  void shouldReturnDetailWithoutLines_whenRefundIsNotLinkedToAnItem() {
    when(refunds.selectById(2L)).thenReturn(refund(2, 10, null));
    when(orders.selectById(10L)).thenReturn(order(10, null, null));
    var detail = service.detail(2L);
    assertThat(detail.lines()).isEmpty();
    assertThat(detail.kolCode()).isNull();
  }

  @Test
  void shouldRejectForeignOrder_whenAccountIsOwnScoped() {
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(7L);
    when(refunds.selectById(1L)).thenReturn(refund(1, 10, null));
    when(orders.selectById(10L)).thenReturn(order(10, null, 8L));
    assertThatThrownBy(() -> service.detail(1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldReturnNotFound_whenRefundIsUnknown() {
    assertThatThrownBy(() -> service.detail(404L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }

  // Task 115 (1105 sheet B12): order-level commission status on the refund list.

  private PageResult<RefundDTO> pageOneRefundOfOrder(OrderDO order) {
    when(refunds.selectPage(any(), any()))
        .thenAnswer(
            call -> {
              Page<RefundDO> page = call.getArgument(0);
              page.setRecords(List.of(refund(1, order.getId(), null)));
              page.setTotal(1);
              return page;
            });
    when(orders.selectByIds(any())).thenReturn(List.of(order));
    var line = new OrderItemDO();
    line.setId(20L);
    line.setOrderId(order.getId());
    line.setKolId(order.getKolId());
    when(items.selectList(any())).thenReturn(List.of(line));
    return service.page(new RefundFilterDTO(null, null, null, null, null, 1, 20));
  }

  private static OrderDO paidOrder(long id, Long kolId, Long bdUserId) {
    var o = order(id, kolId, bdUserId);
    o.setPayTime(PAID);
    return o;
  }

  private static ItemCommissionStateDTO state(long orderId, String status, boolean afterSales) {
    return new ItemCommissionStateDTO(
        orderId, 20L, status, new BigDecimal("0.1"), BigDecimal.ONE, BigDecimal.ZERO, afterSales);
  }

  @ParameterizedTest
  @CsvSource({"FROZEN", "WITHDRAWABLE", "WITHDRAWN", "NO_COMMISSION", "WITHDRAWING", "INVALID"})
  void shouldShowOrderCommissionStatus_whenCallerMaySeeCommission(String status) {
    when(account.effectivePermissions()).thenReturn(List.of("order:view", "commission:view"));
    when(commissionStates.listByKolAndOrderIds(100L, List.of(10L)))
        .thenReturn(List.of(state(10L, status, false)));

    var row = pageOneRefundOfOrder(paidOrder(10, 100L, 7L)).items().getFirst();

    assertThat(row.commissionStatus()).isEqualTo(status);
    assertThat(row.commissionHidden()).isFalse();
    assertThat(row.commissionAfterSales()).isFalse();
  }

  @Test
  void shouldPickMostActiveLineAndFlagAfterSales_whenOrderHasSeveralLines() {
    when(account.effectivePermissions()).thenReturn(List.of("commission:view"));
    when(commissionStates.listByKolAndOrderIds(100L, List.of(10L)))
        .thenReturn(List.of(state(10L, "WITHDRAWN", false), state(10L, "FROZEN", true)));

    var row = pageOneRefundOfOrder(paidOrder(10, 100L, 7L)).items().getFirst();

    // Same ladder as the creator portal: frozen outranks withdrawn.
    assertThat(row.commissionStatus()).isEqualTo("FROZEN");
    assertThat(row.commissionAfterSales()).isTrue();
  }

  @Test
  void shouldHideCommission_whenCallerLacksCommissionView() {
    when(account.effectivePermissions()).thenReturn(List.of("order:view"));

    var row = pageOneRefundOfOrder(paidOrder(10, 100L, 7L)).items().getFirst();

    assertThat(row.commissionHidden()).isTrue();
    assertThat(row.commissionStatus()).isNull();
    verify(commissionStates, org.mockito.Mockito.never()).listByKolAndOrderIds(anyLong(), any());
  }

  @Test
  void shouldHideCommission_whenOwnScopeDoesNotCoverTheOrderCreator() {
    when(account.effectivePermissions()).thenReturn(List.of("commission:view"));
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(7L);
    // The order is still the BD's, but its creator has since moved to another BD.
    when(kols.listByBdUserId(7L)).thenReturn(List.of(new KolRefDTO(300L, "K3", "Other", true, 7L)));

    var row = pageOneRefundOfOrder(paidOrder(10, 100L, 7L)).items().getFirst();

    assertThat(row.commissionHidden()).isTrue();
    assertThat(row.commissionStatus()).isNull();
  }

  @Test
  void shouldShowStatus_whenOwnScopeCoversTheOrderCreator() {
    when(account.effectivePermissions()).thenReturn(List.of("commission:view"));
    when(account.ownScope()).thenReturn(true);
    when(account.id()).thenReturn(7L);
    when(kols.listByBdUserId(7L)).thenReturn(List.of(new KolRefDTO(100L, "K1", "Own", true, 7L)));
    when(commissionStates.listByKolAndOrderIds(100L, List.of(10L)))
        .thenReturn(List.of(state(10L, "WITHDRAWABLE", false)));

    var row = pageOneRefundOfOrder(paidOrder(10, 100L, 7L)).items().getFirst();

    assertThat(row.commissionHidden()).isFalse();
    assertThat(row.commissionStatus()).isEqualTo("WITHDRAWABLE");
  }

  @Test
  void shouldShowNoStatus_whenOrderHasNoCreator() {
    when(account.effectivePermissions()).thenReturn(List.of("commission:view"));

    var row = pageOneRefundOfOrder(paidOrder(10, null, null)).items().getFirst();

    assertThat(row.commissionHidden()).isFalse();
    assertThat(row.commissionStatus()).isNull();
  }
}
