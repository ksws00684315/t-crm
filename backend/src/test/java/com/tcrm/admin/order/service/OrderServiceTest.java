package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderChainCommissionApi;
import com.tcrm.admin.order.mapper.OrderItemMapper;
import com.tcrm.admin.order.mapper.OrderMapper;
import com.tcrm.admin.order.mapper.OrderSyncFailureMapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.mapper.RefundMapper;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderSyncFailureDO;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.CopyJobLookupApi;
import com.tcrm.admin.product.api.CopyJobLookupApi.CopyJobRefDTO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkCodeDTO;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.api.KolMappingEvidenceDTO;
import com.tcrm.admin.product.api.OrderMappingApi;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ProductAccessApi.ProductRefDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
  private final OrderSyncStateMapper syncStates = mock(OrderSyncStateMapper.class);
  private final OrderSyncFailureMapper syncFailures = mock(OrderSyncFailureMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final WeidianAuthorizationApi authorization = mock(WeidianAuthorizationApi.class);
  private final OrderMappingApi mappings = mock(OrderMappingApi.class);
  private final KolOwnershipApi ownership = mock(KolOwnershipApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final KolItemApi kolItems = mock(KolItemApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final CopyJobLookupApi copyJobs = mock(CopyJobLookupApi.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final AuditSanitizer sanitizer = mock(AuditSanitizer.class);
  private final OrderChainCommissionApi commissionChain = mock(OrderChainCommissionApi.class);

  {
    org.mockito.Mockito.when(sanitizer.sanitize(org.mockito.ArgumentMatchers.any()))
        .thenReturn("{}");
  }

  private final AccountDTO account = mock(AccountDTO.class);
  private final OrderService service =
      new OrderService(
          orders,
          items,
          refunds,
          syncStates,
          syncFailures,
          kols,
          shops,
          bdUsers,
          authorization,
          new OrderAttribution(mappings, ownership),
          current,
          kolItems,
          products,
          copyJobs,
          audit,
          sanitizer,
          new OrderCommissionSync(
              mock(org.springframework.context.ApplicationEventPublisher.class), refunds),
          commissionChain);

  @BeforeAll
  static void metadata() {
    for (var type :
        List.of(
            OrderDO.class,
            OrderItemDO.class,
            RefundDO.class,
            OrderSyncStateDO.class,
            OrderSyncFailureDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @BeforeEach
  void currentUser() {
    when(current.user()).thenReturn(account);
    when(account.id()).thenReturn(7L);
    when(shops.listShops())
        .thenReturn(
            List.of(
                new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005"),
                new ShopAccessDTO(6L, "Backup shop", "wd-6", 0, "S006")));
    when(orders.missingOrderFields()).thenReturn(List.of());
    when(orders.latestOrderSourcePerShop()).thenReturn(List.of());
    when(items.missingItemFields()).thenReturn(List.of());
    when(refunds.missingRefundFields()).thenReturn(List.of());
    when(refunds.latestRefundSourcePerShop()).thenReturn(List.of());
    when(syncFailures.selectList(any())).thenReturn(List.of());
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
        5L,
        PAID.minusSeconds(1),
        PAID.plusSeconds(1),
        null,
        null,
        code,
        null,
        "PAID",
        null,
        null,
        false,
        null,
        null,
        1,
        20);
  }

  /** Task 70 filters only; every other condition stays absent. */
  private static OrderFilterDTO task70Filter(
      Instant orderTimeFrom,
      Instant orderTimeTo,
      String kolKeyword,
      String refundState,
      boolean abnormal) {
    return new OrderFilterDTO(
        null,
        null,
        null,
        orderTimeFrom,
        orderTimeTo,
        null,
        kolKeyword,
        null,
        null,
        refundState,
        abnormal,
        null,
        null,
        1,
        20);
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
    a.setRefundStatus("SUCCESS");
    var b = new RefundDO();
    b.setOrderId(10L);
    b.setAmount(new BigDecimal("0.75"));
    b.setRefundStatus("PROCESSING");
    when(refunds.selectList(any())).thenReturn(List.of(a, b));
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(bdUsers.listByIds(any()))
        .thenReturn(List.of(new BdUserDTO(7L, "bd-login", "Test BD", true)));

    var result = service.page(filter("KOL0100"), true);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.items().getFirst().refundAmount()).isEqualByComparingTo("1.25");
    assertThat(result.items().getFirst().refundStatus()).isEqualTo("PROCESSING");
    assertThat(result.items().getFirst().shopName()).isEqualTo("Main shop");
    assertThat(result.items().getFirst().shopCode()).isEqualTo("S005");
    assertThat(result.items().getFirst().bdUserName()).isEqualTo("Test BD");
    assertThat(result.items().get(1).refundAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(1).refundStatus()).isEqualTo("NONE");
    assertThat(result.items().get(1).bdUserName()).isEqualTo("Test BD");
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
                "KOL0100",
                "Creator",
                200L,
                300L,
                2,
                "confirmed",
                null));
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
    // All refund rows load once per page; SUCCESS totals and the order-level status derive in
    // memory (OrderRefundStatuses), so no status predicate appears in SQL.
    assertThat(refundQuery.getValue().getSqlSegment()).contains("order_id IN");
    assertThat(refundQuery.getValue().getParamNameValuePairs().values()).contains(10L, 11L);
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

    TestTransactions.template()
        .executeWithoutResult(txStatus -> service.assign(10L, 100L, false, "confirmed"));

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
    TestTransactions.template()
        .executeWithoutResult(txStatus -> service.assign(10L, null, true, "direct sale"));
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
    TestTransactions.template().executeWithoutResult(txStatus -> service.reattribute(10L));
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
    service.page(
        new OrderFilterDTO(
            null, null, null, null, null, null, null, null, null, null, false, null, null, 1, 20),
        false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("ORDER BY pay_time DESC,id DESC")
        .doesNotContain("shop_id", "order_status", "attribution_status", "bd_user_id");
  }

  @Test
  void shouldConstrainAttributionStatus_whenRequested() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(
        new OrderFilterDTO(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "ATTRIBUTED",
            null,
            false,
            null,
            null,
            1,
            20),
        false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("attribution_status");
    assertThat(query.getValue().getParamNameValuePairs().values()).contains("ATTRIBUTED");
  }

  @Test
  void shouldMatchExactOrderNoAndIgnoreUnknownChannels_whenFiltering() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(
        new OrderFilterDTO(
            null, null, null, null, null, null, null, null, null, null, false, "12345", "WEIDIAN",
            1, 20),
        false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("weidian_order_id");
    assertThat(query.getValue().getParamNameValuePairs().values()).contains("12345");
    // An unknown channel selects nothing instead of silently ignoring the filter.
    assertThat(
            service
                .page(
                    new OrderFilterDTO(
                        null, null, null, null, null, null, null, null, null, null, false, null,
                        "TMALL", 1, 20),
                    false)
                .items())
        .isEmpty();
    verify(orders, times(1)).selectPage(any(), any());
  }

  @Test
  void shouldReturnEmptyPage_whenKolKeywordMatchesNoCreator() {
    when(kols.findIdsByKeyword("nobody")).thenReturn(List.of());
    var result = service.page(task70Filter(null, null, "nobody", null, false), false);
    assertThat(result.items()).isEmpty();
    assertThat(result.total()).isZero();
    verify(orders, never()).selectPage(any(), any());
  }

  @Test
  void shouldApplyOrderTimeKolKeywordRefundAndAbnormalFilters_whenRequested() {
    when(kols.findIdsByKeyword("zoe")).thenReturn(List.of(100L, 200L));
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(
        task70Filter(PAID.minusSeconds(1), PAID.plusSeconds(1), "zoe", "REFUNDED", true), false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders).selectPage(any(), query.capture());
    assertThat(query.getValue().getSqlSegment())
        .contains("order_time", "kol_id IN", "crm_refund", "order_status", "attribution_status");
    assertThat(query.getValue().getParamNameValuePairs().values())
        .contains(PAID.minusSeconds(1), PAID.plusSeconds(1), 100L, 200L, "SUCCESS");
  }

  @Test
  void shouldMapRefundStatesOntoPersistedRefundStatuses_whenFiltering() {
    when(orders.selectPage(any(), any())).thenReturn(new Page<OrderDO>(1, 20, 0));
    service.page(task70Filter(null, null, null, "REFUNDING", false), false);
    service.page(task70Filter(null, null, null, "REFUNDED", false), false);
    ArgumentCaptor<LambdaQueryWrapper<OrderDO>> query = ArgumentCaptor.captor();
    verify(orders, times(2)).selectPage(any(), query.capture());
    // getSqlSegment() first: the EXISTS fragment fills paramNameValuePairs lazily on rendering.
    query.getAllValues().forEach(w -> assertThat(w.getSqlSegment()).contains("crm_refund"));
    assertThat(query.getAllValues().getFirst().getParamNameValuePairs().values())
        .contains("PROCESSING");
    assertThat(query.getAllValues().get(1).getParamNameValuePairs().values()).contains("SUCCESS");
  }

  @Test
  void shouldDecorateDisplayNamesWithFixedLookups_whenPagingOrders() {
    var attributed = order(10);
    attributed.setKolId(100L);
    var companyOwned = order(11);
    companyOwned.setShopId(6L);
    companyOwned.setBdUserId(null);
    var usernameOnly = order(12);
    usernameOnly.setBdUserId(8L);
    when(orders.selectPage(any(), any()))
        .thenAnswer(
            call -> {
              Page<OrderDO> page = call.getArgument(0);
              page.setRecords(List.of(attributed, companyOwned, usernameOnly));
              page.setTotal(3);
              return page;
            });
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(bdUsers.listByIds(any()))
        .thenReturn(
            List.of(
                new BdUserDTO(7L, "bd-login", "Test BD", true),
                new BdUserDTO(8L, "username-only", null, true)));

    var result = service.page(filter(null), false);

    var first = result.items().getFirst();
    assertThat(first.shopName()).isEqualTo("Main shop");
    assertThat(first.shopCode()).isEqualTo("S005");
    assertThat(first.kolCode()).isEqualTo("KOL0100");
    assertThat(first.kolName()).isEqualTo("Creator");
    assertThat(first.bdUserName()).isEqualTo("Test BD");
    assertThat(result.items().get(1).shopName()).isEqualTo("Backup shop");
    assertThat(result.items().get(1).bdUserName()).isNull();
    assertThat(result.items().get(2).bdUserName()).isEqualTo("username-only");
    verify(shops, times(1)).listShops();
    verify(kols, times(1)).listByIds(any());
    verify(bdUsers, times(1)).listByIds(any());
  }

  @Test
  void shouldDecorateEachLineWithItsOwnKolAndImage_whenPagingOrders() {
    var attributed = order(10);
    attributed.setKolId(100L);
    var first = item();
    first.setKolId(100L);
    first.setProductId(300L);
    var second = item();
    second.setId(21L);
    second.setWeidianItemId("91");
    second.setKolId(101L);
    second.setProductId(301L);
    var deletedKolLine = item();
    deletedKolLine.setId(22L);
    deletedKolLine.setWeidianItemId("92");
    deletedKolLine.setKolId(999L);
    deletedKolLine.setProductId(302L);
    var unattributed = item();
    unattributed.setId(23L);
    unattributed.setWeidianItemId("93");
    unattributed.setProductId(303L);
    when(orders.selectPage(any(), any()))
        .thenAnswer(
            call -> {
              Page<OrderDO> page = call.getArgument(0);
              page.setRecords(List.of(attributed));
              page.setTotal(1);
              return page;
            });
    when(items.selectList(any())).thenReturn(List.of(first, second, deletedKolLine, unattributed));
    when(kols.listByIds(any()))
        .thenReturn(
            List.of(
                new KolRefDTO(100L, "KOL0100", "Creator", true, 99L),
                new KolRefDTO(101L, "KOL0101", "Other", true, 99L)));
    when(products.listProducts(any()))
        .thenReturn(
            List.of(
                new ProductRefDTO(300L, "P300", "Product A", "https://img.example/300.png", 1),
                new ProductRefDTO(301L, "P301", "Product B", null, 1)));

    var result = service.page(filter(null), false);

    assertThat(result.total()).isEqualTo(1);
    var lines = result.items().getFirst().items();
    assertThat(lines)
        .extracting(OrderItemDTO::kolCode)
        .containsExactly("KOL0100", "KOL0101", null, null);
    assertThat(lines)
        .extracting(OrderItemDTO::kolName)
        .containsExactly("Creator", "Other", null, null);
    assertThat(lines)
        .extracting(OrderItemDTO::imageUrl)
        .containsExactly("https://img.example/300.png", null, null, null);
    verify(kols, times(1)).listByIds(any());
    verify(products, times(1)).listProducts(any());
    verify(shops, times(1)).listShops();
    verify(bdUsers, times(1)).listByIds(any());
  }

  @Test
  void shouldListEveryShopWithSyncStatus_whenReadingSyncStates() {
    var state = new OrderSyncStateDO();
    state.setShopId(5L);
    state.setLastSuccessAt(PAID);
    state.setLastError("order detail failed for order 1000");
    state.setLastImportKind("ORDER");
    state.setLastImportCutoffAt(PAID.plusSeconds(1));
    state.setLastImportTaskId(33L);
    when(syncStates.selectList(any())).thenReturn(List.of(state));
    when(authorization.authorizedShopIds()).thenReturn(List.of(5L));

    var result = service.listSyncStates();

    var emptyOrders =
        new OrderSyncStateDTO.KindSyncDTO(
            null,
            null,
            null,
            0L,
            Map.of(
                "payTime", 0L,
                "orderTime", 0L,
                "payAmount", 0L,
                "totalAmount", 0L,
                "itemItemId", 0L,
                "itemPayAmount", 0L));
    var emptyRefunds =
        new OrderSyncStateDTO.KindSyncDTO(
            null, null, null, 0L, Map.of("orderItemId", 0L, "finishTime", 0L));
    assertThat(result.get(0).shopId()).isEqualTo(5L);
    assertThat(result.get(0).enabled()).isTrue();
    assertThat(result.get(0).authorized()).isTrue();
    assertThat(result.get(0).lastSuccessAt()).isEqualTo(PAID);
    assertThat(result.get(0).lastError()).isEqualTo("order detail failed for order 1000");
    assertThat(result.get(0).lastImport())
        .isEqualTo(new OrderSyncStateDTO.ImportCutoffDTO("ORDER", PAID.plusSeconds(1), 33L));
    assertThat(result.get(0).orders().lastSuccessAt()).isEqualTo(PAID);
    assertThat(result.get(1).shopId()).isEqualTo(6L);
    assertThat(result.get(1).enabled()).isFalse();
    assertThat(result.get(1).authorized()).isFalse();
    assertThat(result.get(1).orders()).isEqualTo(emptyOrders);
    assertThat(result.get(1).refunds()).isEqualTo(emptyRefunds);
    assertThat(result.get(1).lastImport()).isNull();
  }

  @Test
  void shouldReturnOrderDetailWithLinesAndImages_whenOrderExists() {
    var o = order(10);
    o.setKolId(100L);
    o.setSource(1);
    o.setOrderStatus("PAID");
    o.setFreight(new BigDecimal("1.00"));
    o.setLastSyncTime(PAID);
    o.setOrderTime(PAID.minusSeconds(60));
    when(orders.selectById(10L)).thenReturn(o);
    var i = item();
    i.setProductId(200L);
    i.setKolId(100L);
    i.setQuantity(2);
    i.setPayAmount(new BigDecimal("12.50"));
    i.setItemName("Product");
    i.setSkuName("Size");
    i.setRefundAmount(BigDecimal.ZERO);
    when(items.selectList(any())).thenReturn(List.of(i));
    when(refunds.selectList(any())).thenReturn(List.of());
    when(shops.findShop(5L)).thenReturn(new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005"));
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(products.listProducts(any()))
        .thenReturn(List.of(new ProductRefDTO(200L, "P200", "Original", "https://img/a.png", 1)));

    var detail = service.detail(10L);

    assertThat(detail.orderNo()).isEqualTo("remote10");
    assertThat(detail.channel()).isEqualTo("WEIDIAN");
    assertThat(detail.shopCode()).isEqualTo("S005");
    assertThat(detail.merchantNo()).isEqualTo("wd-5");
    assertThat(detail.source()).isEqualTo("API");
    assertThat(detail.refundStatus()).isEqualTo("NONE");
    assertThat(detail.itemAmount()).isEqualByComparingTo("12.50");
    assertThat(detail.items()).hasSize(1);
    assertThat(detail.items().getFirst().imageUrl()).isEqualTo("https://img/a.png");
    assertThat(detail.items().getFirst().unitPrice()).isEqualByComparingTo("6.25");
    assertThat(detail.items().getFirst().status()).isEqualTo("PAID");
  }

  @Test
  void shouldRejectDetail_whenOwnScopeMissesSnapshot() {
    when(account.ownScope()).thenReturn(true);
    var o = order(10);
    o.setBdUserId(8L);
    when(orders.selectById(10L)).thenReturn(o);
    error(() -> service.detail(10L), ErrorCode.AUTH_FORBIDDEN);
  }

  @Test
  void shouldBuildChainWithCopyJobLinkAndRefunds_whenLineHasMapping() {
    var o = order(10);
    o.setKolId(100L);
    when(orders.selectById(10L)).thenReturn(o);
    var i = item();
    i.setKolItemId(300L);
    i.setKolId(100L);
    i.setQuantity(1);
    i.setPayAmount(new BigDecimal("12.50"));
    i.setSubOrderId("sub-1");
    when(items.selectList(any())).thenReturn(List.of(i));
    var refund = new RefundDO();
    refund.setId(9L);
    refund.setOrderId(10L);
    refund.setOrderItemId(20L);
    refund.setWeidianRefundNo("R9");
    refund.setRefundType("REFUND_ONLY");
    refund.setRefundStatus("SUCCESS");
    refund.setAmount(new BigDecimal("2.00"));
    refund.setFinishTime(PAID.plusSeconds(10));
    when(refunds.selectList(any())).thenReturn(List.of(refund));
    when(kolItems.listMappingsByIds(any()))
        .thenReturn(
            List.of(
                new KolMappingEvidenceDTO(
                    300L, 100L, 200L, "P200", "Original", 5L, "90", PAID.minusSeconds(100), null)));
    when(kolItems.listLinkCodesByIds(any()))
        .thenReturn(List.of(new KolItemLinkCodeDTO(300L, "KPL-AAAAAAAAAAAA")));
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(copyJobs.findByTargetItemId("90"))
        .thenReturn(new CopyJobRefDTO("JOB-1", PAID.minusSeconds(200), "completed"));

    var chain = service.chain(10L);

    assertThat(chain.orderId()).isEqualTo(10L);
    assertThat(chain.lines()).hasSize(1);
    var line = chain.lines().getFirst();
    assertThat(line.copyJob().jobCode()).isEqualTo("JOB-1");
    assertThat(line.link().kolItemId()).isEqualTo(300L);
    assertThat(line.link().targetItemId()).isEqualTo("90");
    assertThat(line.link().linkCode()).isEqualTo("KPL-AAAAAAAAAAAA");
    assertThat(line.refunds()).hasSize(1);
    assertThat(line.refunds().getFirst().refundNo()).isEqualTo("R9");
    // No commission:view in this test, so all financial blocks stay null (task 72).
    assertThat(line.commission()).isNull();
    assertThat(chain.funds()).isNull();
    assertThat(chain.financeFlows()).isNull();
  }

  @Test
  void shouldHideChainFinance_whenViewerLacksCommissionView() {
    when(orders.selectById(10L)).thenReturn(order(10));
    when(items.selectList(any())).thenReturn(List.of(item()));
    when(account.effectivePermissions()).thenReturn(List.of("order:view"));

    var chain = service.chain(10L);

    assertThat(chain.funds()).isNull();
    assertThat(chain.financeFlows()).isNull();
    assertThat(chain.lines().getFirst().commission()).isNull();
    verify(commissionChain, never()).chainOfOrder(anyLong(), any());
  }

  @Test
  void shouldAssembleChainFinance_whenViewerHoldsCommissionView() {
    when(orders.selectById(10L)).thenReturn(order(10));
    when(account.effectivePermissions()).thenReturn(List.of("order:view", "commission:view"));
    when(shops.findShop(5L)).thenReturn(new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005"));
    var i = item();
    i.setKolId(100L);
    i.setQuantity(1);
    i.setPayAmount(new BigDecimal("12.50"));
    when(items.selectList(any())).thenReturn(List.of(i));
    // Same refund id twice (one refund spanning two lines) plus a processing one: 2.00 once.
    var deduped = new RefundDO();
    deduped.setId(9L);
    deduped.setOrderItemId(20L);
    deduped.setRefundStatus("SUCCESS");
    deduped.setAmount(new BigDecimal("2.00"));
    var duplicate = new RefundDO();
    duplicate.setId(9L);
    duplicate.setOrderItemId(21L);
    duplicate.setRefundStatus("SUCCESS");
    duplicate.setAmount(new BigDecimal("2.00"));
    var processing = new RefundDO();
    processing.setId(12L);
    processing.setOrderItemId(20L);
    processing.setRefundStatus("PROCESSING");
    processing.setAmount(new BigDecimal("5.00"));
    when(refunds.selectList(any())).thenReturn(List.of(deduped, duplicate, processing));
    when(commissionChain.chainOfOrder(eq(10L), any()))
        .thenReturn(
            new OrderChainCommissionApi.OrderCommissionChainDTO(
                new BigDecimal("3.75"),
                false,
                List.of(
                    new OrderChainCommissionApi.LineCommissionDTO(
                        20L,
                        new BigDecimal("0.3000"),
                        new BigDecimal("3.75"),
                        "CONFIRMED",
                        new BigDecimal("3.00"),
                        List.of(
                            new OrderChainCommissionApi.WithdrawalRefDTO(
                                31L, "W20261016000001", new BigDecimal("3.00"), "PAID", PAID),
                            new OrderChainCommissionApi.WithdrawalRefDTO(
                                32L,
                                "W20261016000002",
                                new BigDecimal("1.00"),
                                "REJECTED",
                                null)))),
                List.of(
                    new OrderChainCommissionApi.FlowDTO(
                        20L,
                        "CL900",
                        "KOL_COMMISSION",
                        "INCREASE",
                        new BigDecimal("5.00"),
                        PAID,
                        null,
                        null),
                    new OrderChainCommissionApi.FlowDTO(
                        20L,
                        "WA901",
                        "KOL_COMMISSION",
                        "DECREASE",
                        new BigDecimal("3.00"),
                        PAID.plusSeconds(1),
                        PAID.plusSeconds(3600),
                        "W20261016000001"))));

    var chain = service.chain(10L);

    assertThat(chain.funds().payAmount()).isEqualByComparingTo("12.50");
    assertThat(chain.funds().refundTotal()).isEqualByComparingTo("2.00");
    // Any unverified line hides the order total (review: unknown must not pose as zero); the
    // verified line's own block still carries its 3.75.
    assertThat(chain.funds().kolCommission()).isNull();
    assertThat(chain.funds().shopProfit()).isNull();
    assertThat(chain.funds().accountStatus()).isEqualTo("UNVERIFIED");
    var block = chain.lines().getFirst().commission();
    assertThat(block.rateSnapshot()).isEqualByComparingTo("0.3000");
    assertThat(block.netCommission()).isEqualByComparingTo("3.75");
    assertThat(block.shopProfit()).isNull();
    assertThat(block.releaseBasis()).isEqualTo("CONFIRMED");
    assertThat(block.occupiedTotal()).isEqualByComparingTo("3.00");
    assertThat(block.withdrawals()).hasSize(2);
    assertThat(block.withdrawals().getFirst().applicationNo()).isEqualTo("W20261016000001");
    assertThat(block.withdrawals().getFirst().paidAt()).isEqualTo(PAID);
    assertThat(chain.financeFlows()).hasSize(2);
    assertThat(chain.financeFlows().getFirst().flowNo()).isEqualTo("CL900");
    assertThat(chain.financeFlows().getFirst().shopName()).isEqualTo("Main shop");
    assertThat(chain.financeFlows().get(1).transferredAt()).isEqualTo(PAID.plusSeconds(3600));
    assertThat(chain.financeFlows().get(1).withdrawalNo()).isEqualTo("W20261016000001");
  }

  @Test
  void shouldHideChainFinance_fromOwnViewerOnMixedAttribution() {
    when(orders.selectById(10L)).thenReturn(order(10));
    when(account.effectivePermissions()).thenReturn(List.of("order:view", "commission:view"));
    when(account.ownScope()).thenReturn(true);
    var mine = item();
    mine.setId(20L);
    mine.setKolId(100L);
    var other = item();
    other.setId(21L);
    other.setKolId(101L);
    when(items.selectList(any())).thenReturn(List.of(mine, other));
    when(kols.listByBdUserId(7L))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 7L)));

    var chain = service.chain(10L);

    assertThat(chain.funds()).isNull();
    assertThat(chain.financeFlows()).isNull();
    assertThat(chain.lines()).extracting(l -> l.commission()).containsOnlyNulls();
    verify(commissionChain, never()).chainOfOrder(anyLong(), any());
  }

  @Test
  void shouldShowChainFinance_toOwnViewerCoveringEveryAttributedLine() {
    when(orders.selectById(10L)).thenReturn(order(10));
    when(account.effectivePermissions()).thenReturn(List.of("order:view", "commission:view"));
    when(account.ownScope()).thenReturn(true);
    var mine = item();
    mine.setId(20L);
    mine.setKolId(100L);
    // Unattributed lines never block the OWN coverage (same basis as settlement details).
    var unattributed = item();
    unattributed.setId(21L);
    when(items.selectList(any())).thenReturn(List.of(mine, unattributed));
    when(kols.listByBdUserId(7L))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 7L)));
    when(commissionChain.chainOfOrder(eq(10L), any()))
        .thenReturn(
            new OrderChainCommissionApi.OrderCommissionChainDTO(
                new BigDecimal("0.00"), true, List.of(), List.of()));

    var chain = service.chain(10L);

    assertThat(chain.funds()).isNotNull();
    // Every line verified: the order total returns the real sum, zero included.
    assertThat(chain.funds().kolCommission()).isEqualByComparingTo("0.00");
    assertThat(chain.funds().accountStatus()).isEqualTo("POSTED");
  }

  @Test
  void shouldExplainEvidenceReasons_forMappingCoverageAndConflict() {
    var o = order(10);
    o.setAttributionStatus("UNMATCHED");
    when(orders.selectById(10L)).thenReturn(o);
    var i = item();
    i.setWeidianItemId("90");
    i.setItemName("Exclusive");
    i.setAttributionType(0);
    when(items.selectOne(any())).thenReturn(i);
    when(shops.findShop(5L)).thenReturn(new ShopAccessDTO(5L, "Main shop", "wd-5", 1, "S005"));
    // Mapping ended before pay time → MAPPING_EXPIRED.
    when(kolItems.listMappingsByShopItem(5L, "90"))
        .thenReturn(
            List.of(
                new KolMappingEvidenceDTO(
                    300L,
                    100L,
                    200L,
                    "P200",
                    "Original",
                    5L,
                    "90",
                    PAID.minusSeconds(200),
                    PAID.minusSeconds(1))));
    when(kolItems.listLinkCodesByIds(any()))
        .thenReturn(List.of(new KolItemLinkCodeDTO(300L, "KPL-BBBBBBBBBBBB")));
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(100L, "KOL0100", "Creator", true, 99L)));
    when(products.getProduct(200L))
        .thenReturn(new ProductRefDTO(200L, "P200", "Original", null, 1));

    var expired = service.evidence(20L);
    assertThat(expired.unmatchedReason()).isEqualTo("MAPPING_EXPIRED");
    assertThat(expired.mappings().getFirst().coversPayTime()).isFalse();
    assertThat(expired.mappings().getFirst().linkCode()).isEqualTo("KPL-BBBBBBBBBBBB");
    assertThat(expired.product().productCode()).isEqualTo("P200");

    when(kolItems.listMappingsByShopItem(5L, "90")).thenReturn(List.of());
    assertThat(service.evidence(20L).unmatchedReason()).isEqualTo("NO_MAPPING");

    o.setAttributionStatus("CONFLICT");
    when(kolItems.listMappingsByShopItem(5L, "90"))
        .thenReturn(
            List.of(
                new KolMappingEvidenceDTO(
                    300L, 100L, 200L, "P200", "Original", 5L, "90", PAID.minusSeconds(200), null)));
    assertThat(service.evidence(20L).unmatchedReason()).isEqualTo("CONFLICT");

    o.setAttributionStatus("PENDING");
    o.setPayTime(null);
    assertThat(service.evidence(20L).unmatchedReason()).isEqualTo("PENDING");
  }

  @Test
  void shouldReturnNotFound_whenEvidenceLineIsMissing() {
    when(items.selectOne(any())).thenReturn(null);
    error(() -> service.evidence(404L), ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldAggregateMissingFieldsFailuresAndSources_whenListingSyncStates() {
    var state = new OrderSyncStateDO();
    state.setShopId(5L);
    state.setLastSuccessAt(PAID);
    state.setLastError("detail failed");
    state.setLastImportKind("ORDER");
    state.setLastImportCutoffAt(PAID);
    state.setLastImportTaskId(33L);
    when(syncStates.selectList(any())).thenReturn(List.of(state));
    when(authorization.authorizedShopIds()).thenReturn(List.of(5L));

    var orderQuality = new OrderMapper.ShopQualityRow();
    orderQuality.setShopId(5L);
    orderQuality.setMissingPayTime(2L);
    orderQuality.setMissingOrderTime(1L);
    orderQuality.setMissingPayAmount(0L);
    orderQuality.setMissingTotalAmount(null);
    when(orders.missingOrderFields()).thenReturn(List.of(orderQuality));

    var itemQuality = new OrderItemMapper.ItemQualityRow();
    itemQuality.setShopId(5L);
    itemQuality.setMissingItemId(3L);
    itemQuality.setMissingPayAmount(4L);
    when(items.missingItemFields()).thenReturn(List.of(itemQuality));

    var refundQuality = new RefundMapper.RefundQualityRow();
    refundQuality.setShopId(5L);
    refundQuality.setMissingOrderItemId(5L);
    refundQuality.setMissingFinishTime(6L);
    when(refunds.missingRefundFields()).thenReturn(List.of(refundQuality));

    var orderSource = new OrderMapper.ShopSourceRow();
    orderSource.setShopId(5L);
    orderSource.setLatestSource(1);
    when(orders.latestOrderSourcePerShop()).thenReturn(List.of(orderSource));
    var refundSource = new RefundMapper.RefundSourceRow();
    refundSource.setShopId(5L);
    refundSource.setLatestSource(2);
    when(refunds.latestRefundSourcePerShop()).thenReturn(List.of(refundSource));

    var failure = new OrderSyncFailureDO();
    failure.setShopId(5L);
    failure.setKind("ORDER_SYNC");
    failure.setCreateTime(PAID);
    when(syncFailures.selectList(any())).thenReturn(List.of(failure, failure));

    var result = service.listSyncStates();
    assertThat(result.getFirst().orders().source()).isEqualTo("API");
    assertThat(result.getFirst().orders().failures24h()).isEqualTo(2L);
    assertThat(result.getFirst().orders().missingFields().get("payTime")).isEqualTo(2L);
    assertThat(result.getFirst().orders().missingFields().get("itemItemId")).isEqualTo(3L);
    assertThat(result.getFirst().refunds().source()).isEqualTo("IMPORT");
    assertThat(result.getFirst().refunds().missingFields().get("finishTime")).isEqualTo(6L);
  }

  @Test
  void shouldListRecentSyncFailures_withShopNames() {
    var failure = new OrderSyncFailureDO();
    failure.setId(1L);
    failure.setShopId(5L);
    failure.setKind("ORDER_IMPORT");
    failure.setError("row failed");
    failure.setTaskId(77L);
    failure.setCreateTime(PAID);
    var orphan = new OrderSyncFailureDO();
    orphan.setId(2L);
    orphan.setShopId(null);
    orphan.setKind("REFUND_IMPORT");
    orphan.setError("partial");
    orphan.setTaskId(78L);
    orphan.setCreateTime(PAID);
    when(syncFailures.selectList(any())).thenReturn(List.of(failure, orphan));

    var rows = service.listSyncFailures();
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().shopName()).isEqualTo("Main shop");
    assertThat(rows.getFirst().taskId()).isEqualTo(77L);
    assertThat(rows.get(1).shopName()).isNull();
  }
}
