package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.api.OrderMappingApi;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderServiceTest {
  private static final Instant PAID = Instant.parse("2026-10-16T00:00:00Z");
  private final OrderMapper orders = mock(OrderMapper.class);
  private final OrderItemMapper items = mock(OrderItemMapper.class);
  private final RefundMapper refunds = mock(RefundMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final OrderMappingApi mappings = mock(OrderMappingApi.class);
  private final KolOwnershipApi ownership = mock(KolOwnershipApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final AccountDTO account = mock(AccountDTO.class);
  private final OrderService service =
      new OrderService(
          orders, items, refunds, kols, new OrderAttribution(mappings, ownership), current);

  @BeforeAll
  static void metadata() {
    for (var type : List.of(OrderDO.class, OrderItemDO.class, RefundDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @BeforeEach
  void currentUser() {
    when(current.user()).thenReturn(account);
    when(account.id()).thenReturn(7L);
  }

  private static OrderDO order(long id) {
    var o = new OrderDO();
    o.setId(id);
    o.setShopId(5L);
    o.setPayTime(PAID);
    o.setWeidianOrderId("remote" + id);
    o.setBdUserId(7L);
    o.setTotalAmount(new BigDecimal("12.50"));
    o.setPayAmount(new BigDecimal("12.50"));
    return o;
  }

  private static OrderItemDO item() {
    var i = new OrderItemDO();
    i.setId(20L);
    i.setOrderId(10L);
    i.setWeidianItemId("90");
    i.setAttributionType(0);
    return i;
  }

  private static OrderFilterDTO filter(String code) {
    return new OrderFilterDTO(
        5L, PAID.minusSeconds(1), PAID.plusSeconds(1), code, "PAID", null, 1, 20);
  }

  private static void error(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(expected);
  }

  @Test
  void shouldReturnEmptyPage_whenKolCodeDoesNotExist() {
    assertThat(service.page(filter("missing"), false).items()).isEmpty();
    verify(orders, never()).selectPage(any(), any());
  }

  @Test
  void shouldAvoidChildQueries_whenPageIsEmpty() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    var result = service.page(filter(" "), false);
    assertThat(result.total()).isZero();
    assertThat(result.page()).isEqualTo(1);
    verify(items, never()).selectList(any());
    verify(refunds, never()).selectList(any());
  }

  @Test
  void shouldApplyOwnSnapshotAndPoolFilters_whenListingUnmatchedOrders() {
    when(account.ownScope()).thenReturn(true);
    when(kols.getByCode("KOL0100"))
        .thenReturn(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L));
    when(orders.selectPage(any(), any()))
        .thenAnswer(
            call -> {
              Page<OrderDO> page = call.getArgument(0);
              page.setRecords(List.of(order(10), order(11)));
              page.setTotal(2);
              return page;
            });
    var i = item();
    i.setSubOrderId("sub");
    i.setSkuId("sku");
    i.setItemName("Product");
    i.setSkuName("Size");
    i.setQuantity(2);
    i.setPayAmount(new BigDecimal("12.50"));
    i.setRefundAmount(new BigDecimal("2.00"));
    i.setKolId(100L);
    i.setProductId(200L);
    i.setKolItemId(300L);
    i.setAttributionType(2);
    i.setManualReason("confirmed");
    when(items.selectList(any())).thenReturn(List.of(i));
    var a = new RefundDO();
    a.setOrderId(10L);
    a.setAmount(new BigDecimal("1.25"));
    var b = new RefundDO();
    b.setOrderId(10L);
    b.setAmount(new BigDecimal("0.75"));
    when(refunds.selectList(any())).thenReturn(List.of(a, b));

    var result = service.page(filter("KOL0100"), true);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.items().getFirst().refundAmount()).isEqualByComparingTo("2.00");
    assertThat(result.items().get(1).refundAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(1).items()).isEmpty();
    assertThat(result.items().getFirst().items().getFirst())
        .isEqualTo(
            new OrderItemDTO(
                20L,
                "sub",
                "90",
                "sku",
                "Product",
                "Size",
                2,
                new BigDecimal("12.50"),
                new BigDecimal("2.00"),
                100L,
                200L,
                300L,
                2,
                "confirmed"));
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains(
            "bd_user_id",
            "pay_time",
            "kol_id",
            "attribution_status",
            "ORDER BY pay_time DESC,id DESC");
    assertThat(query.getValue().getParamNameValuePairs().values())
        .contains(7L, 100L, "CONFLICT", "UNMATCHED", PAID.minusSeconds(1), PAID.plusSeconds(1));
    ArgumentCaptor<LambdaQueryWrapper<RefundDO>> refundQuery = ArgumentCaptor.captor();
    verify(refunds).selectList(refundQuery.capture());
    assertThat(refundQuery.getValue().getSqlSegment()).contains("raw_status");
    assertThat(refundQuery.getValue().getParamNameValuePairs().values()).contains("10");
  }

  @Test
  void shouldRejectInvalidTargetsAndReasons_whenAssigning() {
    for (String reason : Arrays.asList(null, " ", "x".repeat(256))) {
      error(() -> service.assign(10L, 100L, false, reason), ErrorCode.COMMON_VALIDATION_FAILED);
    }
    error(() -> service.assign(10L, null, false, "reason"), ErrorCode.COMMON_VALIDATION_FAILED);
    error(() -> service.assign(10L, 100L, true, "reason"), ErrorCode.COMMON_VALIDATION_FAILED);
    verify(orders, never()).selectOne(any());
  }

  @Test
  void shouldReturnNotFound_whenOrderIsOutsideTheCompany() {
    error(() -> service.assign(10L, 100L, false, "reason"), ErrorCode.COMMON_NOT_FOUND);
    error(() -> service.reattribute(10L), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldRejectOwnScopeWrite_whenSnapshotBelongsToAnotherBd() {
    when(account.ownScope()).thenReturn(true);
    var o = order(10);
    o.setBdUserId(8L);
    when(orders.selectOne(any())).thenReturn(o);
    error(() -> service.assign(10L, null, true, "reason"), ErrorCode.AUTH_FORBIDDEN);
    error(() -> service.reattribute(10L), ErrorCode.AUTH_FORBIDDEN);
    verify(orders, never()).updateById(any(OrderDO.class));
  }

  @Test
  void shouldRejectManualAttribution_whenOrderIsUnpaid() {
    var o = order(10);
    o.setPayTime(null);
    when(orders.selectOne(any())).thenReturn(o);
    error(() -> service.assign(10L, 100L, false, "reason"), ErrorCode.COMMON_CONFLICT);
  }

  @Test
  void shouldRejectForeignKol_whenTargetLookupIsEmpty() {
    when(orders.selectOne(any())).thenReturn(order(10));
    when(kols.listByIds(List.of(100L))).thenReturn(List.of());
    error(() -> service.assign(10L, 100L, false, "reason"), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldPersistWholeOrderAndBdSnapshot_whenAssigningCompanyKol() {
    var o = order(10);
    var i = item();
    i.setKolItemId(300L);
    when(account.ownScope()).thenReturn(true);
    when(orders.selectOne(any())).thenReturn(o);
    when(items.selectList(any())).thenReturn(List.of(i));
    when(kols.listByIds(List.of(100L)))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(ownership.bdAt(100L, PAID)).thenReturn(8L);

    service.assign(10L, 100L, false, "confirmed");

    assertThat(o.getKolId()).isEqualTo(100L);
    assertThat(o.getBdUserId()).isEqualTo(8L);
    assertThat(o.getAttributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(i.getAttributionType()).isEqualTo(2);
    assertThat(i.getKolItemId()).isNull();
    assertThat(i.getManualReason()).isEqualTo("confirmed");
    verify(orders).updateById(o);
    verify(items).updateById(i);
  }

  @Test
  void shouldClearKolAndBd_whenMarkingNonKol() {
    var o = order(10);
    o.setKolId(100L);
    var i = item();
    when(orders.selectOne(any())).thenReturn(o);
    when(items.selectList(any())).thenReturn(List.of(i));
    service.assign(10L, null, true, "direct sale");
    assertThat(o.getKolId()).isNull();
    assertThat(o.getBdUserId()).isNull();
    assertThat(o.getAttributionStatus()).isEqualTo("NON_KOL");
    assertThat(i.getAttributionType()).isEqualTo(3);
    verify(kols, never()).listByIds(any());
  }

  @Test
  void shouldRecomputeMappingAndSnapshot_whenExplicitlyReattributed() {
    var o = order(10);
    var i = item();
    i.setAttributionType(2);
    i.setKolId(99L);
    i.setManualReason("old decision");
    when(orders.selectOne(any())).thenReturn(o);
    when(items.selectList(any())).thenReturn(List.of(i));
    when(mappings.findAt(5L, "90", PAID))
        .thenReturn(List.of(new KolItemRefDTO(300L, 100L, 200L, 1L)));
    when(ownership.bdAt(100L, PAID)).thenReturn(null);
    service.reattribute(10L);
    assertThat(o.getKolId()).isEqualTo(100L);
    assertThat(o.getBdUserId()).isNull();
    assertThat(i.getAttributionType()).isEqualTo(1);
    assertThat(i.getKolItemId()).isEqualTo(300L);
    assertThat(i.getManualReason()).isNull();
    verify(orders).updateById(o);
  }

  @Test
  void shouldApplyOnlyRequestedFilters_whenOptionalQueryFieldsAreAbsent() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(new OrderFilterDTO(null, null, null, null, null, null, 1, 20), false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("ORDER BY pay_time DESC,id DESC")
        .doesNotContain("shop_id", "order_status", "attribution_status", "bd_user_id");
  }

  @Test
  void shouldConstrainAttributionStatus_whenRequested() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(new OrderFilterDTO(null, null, null, null, null, "ATTRIBUTED", 1, 20), false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("attribution_status");
    assertThat(query.getValue().getParamNameValuePairs().values()).contains("ATTRIBUTED");
  }
}
