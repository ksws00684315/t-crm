package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.mapper.CommissionItemRevisionMapper;
import com.tcrm.admin.commission.mapper.CommissionItemStateMapper;
import com.tcrm.admin.commission.model.CommissionItemRevisionDO;
import com.tcrm.admin.commission.model.CommissionItemStateDO;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.DetailFilter;
import com.tcrm.admin.commission.service.CommissionSettlementDetailService.SettlementRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi;
import com.tcrm.admin.order.api.OrderSettlementApi.ItemRefDTO;
import com.tcrm.admin.order.api.OrderSettlementApi.OrderRefDTO;
import com.tcrm.admin.product.api.ProductAccessApi;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests of the settlement detail list (requirement 13.05). */
class CommissionSettlementDetailServiceTest {
  private static final long TENANT = 7L;
  private static final Instant PAY = Instant.parse("2026-10-10T08:00:00Z");

  private final CommissionItemStateMapper states = mock(CommissionItemStateMapper.class);
  private final CommissionItemRevisionMapper revisions = mock(CommissionItemRevisionMapper.class);
  private final OrderSettlementApi orderSettlement = mock(OrderSettlementApi.class);
  private final KolApi kols = mock(KolApi.class);
  private final ProductAccessApi products = mock(ProductAccessApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final CommissionSettlementDetailService service =
      new CommissionSettlementDetailService(
          states, revisions, orderSettlement, kols, products, shops);

  @BeforeEach
  void prepare() {
    lenient().when(states.listByOrderIds(anyCollection())).thenReturn(List.of());
    lenient().when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of());
    lenient().when(orderSettlement.listOrdersByIds(anyCollection())).thenReturn(List.of());
    lenient().when(orderSettlement.listItemsByOrderIds(anyCollection())).thenReturn(List.of());
    lenient().when(products.listProducts(anyCollection())).thenReturn(List.of());
    lenient().when(shops.listShops()).thenReturn(List.of());
    lenient().when(kols.listByIds(anyCollection())).thenReturn(List.of());
    lenient().when(kols.listByBdUserId(anyLong())).thenReturn(List.of());
  }

  @Test
  void shouldRejectForeignChannelAndMissingTenant() {
    assertThat(
            TenantScope.callCompany(
                TENANT,
                () ->
                    service
                        .page(superAdmin(), new DetailFilter(null, "TAOBAO", null, 1, 20))
                        .total()))
        .isZero();
    assertThatThrownBy(
            () ->
                service.page(
                    new AccountDTO(
                        5L,
                        "u",
                        null,
                        "n",
                        "e@t.test",
                        Role.STAFF,
                        null,
                        1,
                        null,
                        null,
                        0,
                        null,
                        null,
                        true,
                        java.util.Set.of(),
                        null),
                    new DetailFilter(null, null, null, 1, 20)))
        .isInstanceOf(com.tcrm.admin.shared.BusinessException.class);
    verifyNoInteractions(orderSettlement);
  }

  @Test
  void shouldHideEverythingFromOwnScopeWithoutKols() {
    AccountDTO bd = staff(true);
    PageResult<SettlementRow> page =
        TenantScope.callCompany(
            TENANT, () -> service.page(bd, new DetailFilter(null, null, null, 1, 20)));
    assertThat(page.total()).isZero();
    assertThat(page.items()).isEmpty();
  }

  @Test
  void shouldBuildPostedRowWithWithdrawableAndLineDetails() {
    when(orderSettlement.pageOrderIds(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(5001L), 1, 1, 20));
    when(orderSettlement.listOrdersByIds(List.of(5001L)))
        .thenReturn(
            List.of(
                new OrderRefDTO(5001L, "WD-1", 30L, PAY, "COMPLETED", new BigDecimal("200.00"))));
    when(orderSettlement.weidianWithdrawableByOrderIds(anyCollection()))
        .thenReturn(Map.of(5001L, new BigDecimal("180.00")));
    when(orderSettlement.listItemsByOrderIds(List.of(5001L)))
        .thenReturn(
            List.of(
                new ItemRefDTO(
                    9001L,
                    5001L,
                    "item",
                    "sku",
                    2,
                    new BigDecimal("200.00"),
                    new BigDecimal("20.00"),
                    77L,
                    51L)));
    when(shops.listShops())
        .thenReturn(List.of(new ShopAccessDTO(30L, "Shop A", "WD-SHOP", 1, "SC-1")));
    when(kols.listByIds(anyCollection()))
        .thenReturn(List.of(new KolRefDTO(77L, "KOL001", "Kol A", true, 9L)));
    CommissionItemStateDO row = stateRow(5001L, 9001L, 77L, "18.00", "18.00", true, "CONFIRMED");
    when(states.listByOrderIds(List.of(5001L))).thenReturn(List.of(row));
    CommissionItemRevisionDO revision = new CommissionItemRevisionDO();
    revision.setItemStateId(row.getId());
    revision.setRate(new BigDecimal("0.1000"));
    when(revisions.listLatestByItemStateIds(anyCollection())).thenReturn(List.of(revision));

    PageResult<SettlementRow> page =
        TenantScope.callCompany(
            TENANT,
            () -> service.page(superAdmin(), new DetailFilter("WD-1", "WEIDIAN", 30L, 1, 20)));
    SettlementRow settlement = page.items().getFirst();
    // Payment is the order pay amount (freight included); the withdrawable total is display only.
    // Accrued 18 is fully released, so the row is POSTED.
    assertThat(settlement.payment()).isEqualTo("200.00");
    assertThat(settlement.refund()).isEqualTo("20.00");
    assertThat(settlement.weidianWithdrawable()).isEqualTo("180.00");
    assertThat(settlement.commission()).isEqualTo("18.00");
    assertThat(settlement.status()).isEqualTo("POSTED");
    assertThat(settlement.kolNames()).containsExactly("Kol A");
    assertThat(settlement.lines().getFirst().product()).isEqualTo("item");
    assertThat(settlement.lines().getFirst().sku()).isEqualTo("sku");
    assertThat(settlement.lines().getFirst().rate()).isEqualTo("0.1000");
    assertThat(settlement.lines().getFirst().direction()).isEqualTo("CREDIT");
    assertThat(settlement.lines().getFirst().subject()).isEqualTo("KOL");
  }

  @Test
  void shouldMarkUnverifiedWithNullWithdrawableAndNullishParts() {
    when(orderSettlement.pageOrderIds(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(5002L), 1, 1, 20));
    when(orderSettlement.listOrdersByIds(List.of(5002L)))
        .thenReturn(
            List.of(new OrderRefDTO(5002L, "WD-2", 30L, PAY, "PAID", new BigDecimal("100.00"))));
    when(orderSettlement.listItemsByOrderIds(List.of(5002L)))
        .thenReturn(
            List.of(
                new ItemRefDTO(
                    9002L,
                    5002L,
                    "Solo",
                    null,
                    1,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    null,
                    null)));
    when(states.listByOrderIds(List.of(5002L)))
        .thenReturn(List.of(stateRow(5002L, 9002L, null, "0.00", "0.00", false, "UNVERIFIED")));
    PageResult<SettlementRow> page =
        TenantScope.callCompany(
            TENANT, () -> service.page(superAdmin(), new DetailFilter(null, null, null, 1, 20)));
    SettlementRow settlement = page.items().getFirst();
    assertThat(settlement.status()).isEqualTo("UNVERIFIED");
    assertThat(settlement.weidianWithdrawable()).isNull();
    assertThat(settlement.kolNames()).isEmpty();
    assertThat(settlement.shop()).isNull();
    assertThat(settlement.lines().getFirst().subject()).isEqualTo("COMPANY");
    assertThat(settlement.lines().getFirst().product()).isEqualTo("Solo");
    assertThat(settlement.lines().getFirst().sku()).isNull();
  }

  @Test
  void shouldMarkFrozenWhileAccruedCommissionAwaitsRelease() {
    // Paid and verified but not yet released (task 81): FROZEN, and no withdrawable total before
    // the Weidian settlement flow arrives.
    when(orderSettlement.pageOrderIds(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(5003L), 1, 1, 20));
    when(orderSettlement.listOrdersByIds(List.of(5003L)))
        .thenReturn(
            List.of(new OrderRefDTO(5003L, "WD-3", 30L, PAY, "PAID", new BigDecimal("65.00"))));
    when(orderSettlement.listItemsByOrderIds(List.of(5003L)))
        .thenReturn(
            List.of(
                new ItemRefDTO(
                    9003L,
                    5003L,
                    "Goods A",
                    null,
                    1,
                    new BigDecimal("65.00"),
                    BigDecimal.ZERO,
                    77L,
                    51L)));
    when(states.listByOrderIds(List.of(5003L)))
        .thenReturn(List.of(stateRow(5003L, 9003L, 77L, "6.50", "0.00", true, "UNSETTLED")));
    PageResult<SettlementRow> page =
        TenantScope.callCompany(
            TENANT, () -> service.page(superAdmin(), new DetailFilter(null, null, null, 1, 20)));
    SettlementRow settlement = page.items().getFirst();
    assertThat(settlement.payment()).isEqualTo("65.00");
    assertThat(settlement.commission()).isEqualTo("6.50");
    assertThat(settlement.weidianWithdrawable()).isNull();
    assertThat(settlement.status()).isEqualTo("FROZEN");
  }

  private static AccountDTO superAdmin() {
    return new AccountDTO(
        5L,
        "t62_admin",
        null,
        "Task 62",
        "t62@example.test",
        Role.SUPER_ADMIN,
        null,
        1,
        null,
        null,
        0,
        TENANT,
        "Task 62 Co",
        true,
        java.util.Set.of(),
        null);
  }

  private static AccountDTO staff(boolean own) {
    return new AccountDTO(
        99L,
        "t62_bd",
        null,
        "BD",
        "bd@t62.test",
        Role.STAFF,
        own ? com.tcrm.admin.security.api.DataScope.OWN : com.tcrm.admin.security.api.DataScope.ALL,
        1,
        null,
        null,
        0,
        TENANT,
        "Task 62 Co",
        true,
        java.util.Set.of(),
        null);
  }

  private static CommissionItemStateDO stateRow(
      long orderId,
      long itemId,
      Long kolId,
      String accrued,
      String released,
      boolean verified,
      String basis) {
    CommissionItemStateDO row = new CommissionItemStateDO();
    row.setId(7000L + itemId % 100);
    row.setTenantId(TENANT);
    row.setOrderId(orderId);
    row.setOrderItemId(itemId);
    row.setKolId(kolId);
    row.setVerified(verified);
    row.setAccruedCommissionCny(new BigDecimal(accrued));
    row.setReleasedCommissionCny(new BigDecimal(released));
    row.setReleaseBasis(basis);
    row.setBasisCny(verified ? new BigDecimal("180.00") : null);
    return row;
  }
}
