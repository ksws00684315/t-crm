package com.tcrm.admin.commission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.commission.api.CreatorFundFlowApi.Direction;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.FundFlow;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.Query;
import com.tcrm.admin.commission.api.CreatorFundFlowApi.Status;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper;
import com.tcrm.admin.commission.mapper.CommissionFinanceQueryMapper.PrepayPaymentRow;
import com.tcrm.admin.commission.mapper.CommissionWalletMapper;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper.Posting;
import com.tcrm.admin.commission.mapper.CreatorFundFlowMapper.Snapshot;
import com.tcrm.admin.commission.mapper.WithdrawalMapper;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.KolAmountRow;
import com.tcrm.admin.commission.mapper.WithdrawalMapper.OccupationRow;
import com.tcrm.admin.commission.service.WithdrawalFundingSource.FundingRow;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.shared.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreatorFundFlowServiceTest {
  private static final Instant FROM = Instant.parse("2026-11-22T16:00:00Z");
  private static final Instant UNTIL = Instant.parse("2026-11-23T16:00:00Z");
  private static final long TENANT = 42;
  private static final long CREATOR = 15;
  private static final long OWNER = 16;
  private final CreatorFundFlowMapper flows = mock(CreatorFundFlowMapper.class);
  private final CommissionFinanceQueryMapper finance = mock(CommissionFinanceQueryMapper.class);
  private final CommissionWalletMapper wallet = mock(CommissionWalletMapper.class);
  private final WithdrawalMapper withdrawals = mock(WithdrawalMapper.class);
  private final WithdrawalFundingSource funding = mock(WithdrawalFundingSource.class);
  private final KolApi creators = mock(KolApi.class);
  private final ShopAccessApi shops = mock(ShopAccessApi.class);
  private final AccountDTO user = mock(AccountDTO.class);
  private final CreatorFundFlowService service =
      new CreatorFundFlowService(flows, finance, wallet, withdrawals, funding, creators, shops);

  @BeforeEach
  void setup() {
    when(user.tenantId()).thenReturn(TENANT);
    when(user.id()).thenReturn(OWNER);
    when(creators.listByIds(List.of(CREATOR)))
        .thenReturn(List.of(new KolRefDTO(CREATOR, "T01", "T01", true, OWNER)));
    when(shops.listShops())
        .thenReturn(
            List.of(
                new ShopAccessDTO(1L, "Shop 1", "wd1", 1, "S1"),
                new ShopAccessDTO(2L, "Shop 2", "wd2", 1, "S2")));
  }

  @Test
  void showsCurrentRemainderAndActualReceiptsWithoutCountingApplicationTwice() {
    when(flows.snapshots(TENANT, CREATOR))
        .thenReturn(List.of(item(1, 7, 1, FROM, "100", "90"), item(2, 7, 1, FROM, "15", "0")));
    when(wallet.findBalance(CREATOR)).thenReturn(money("60"));
    when(withdrawals.sumActiveOccupiedByKol(List.of(CREATOR)))
        .thenReturn(List.of(new KolAmountRow(CREATOR, money("20"))));
    when(withdrawals.listOccupationsByKol(CREATOR))
        .thenReturn(
            List.of(
                new OccupationRow(
                    1, 1, "W1", "PAID", money("20"), money("90"), money("70"), FROM, FROM),
                new OccupationRow(
                    1, 2, "W2", "PAID", money("10"), money("70"), money("60"), FROM, FROM),
                new OccupationRow(
                    1,
                    3,
                    "W3",
                    "PENDING_PAYMENT",
                    money("20"),
                    money("60"),
                    money("40"),
                    FROM,
                    null)));
    when(funding.listReleasedRows(CREATOR)).thenReturn(List.of(new FundingRow(1, 1, money("40"))));
    when(flows.withdrawals(TENANT, CREATOR, FROM, UNTIL))
        .thenReturn(
            List.of(
                new Posting("1", "O7", 1L, OWNER, FROM.plusSeconds(1), money("20"), "W1"),
                new Posting("2", "O7", 1L, OWNER, FROM.plusSeconds(2), money("10"), "W2")));
    when(flows.clawbacks(TENANT, CREATOR, FROM, UNTIL))
        .thenReturn(
            List.of(new Posting("3", "O7", 1L, OWNER, FROM.plusSeconds(3), money("-4"), null)));
    when(finance.prepayPayments(List.of(CREATOR), FROM, UNTIL))
        .thenReturn(
            List.of(
                new PrepayPaymentRow(
                    4L,
                    4L,
                    "P4",
                    "PREPAY",
                    CREATOR,
                    money("50"),
                    FROM.plusSeconds(4),
                    null,
                    null,
                    true,
                    "PAID",
                    FROM)));

    var actual = service.list(user, query(null, 1, 20)).items();

    assertThat(actual)
        .extracting(FundFlow::status)
        .containsExactly(
            Status.PREPAID,
            Status.CLAWBACK,
            Status.WITHDRAWN,
            Status.WITHDRAWN,
            Status.AVAILABLE,
            Status.FROZEN);
    assertThat(actual)
        .extracting(FundFlow::amount)
        .containsExactly(
            money("50"), money("-4"), money("10"), money("20"), money("40"), money("25"));
    assertThat(actual)
        .extracting(FundFlow::settlementNo)
        .containsExactly("P4", null, "W2", "W1", null, null);
    assertThat(actual.get(0).orderNo()).isNull();
    assertThat(actual.get(1).direction()).isEqualTo(Direction.DECREASE);
    assertThat(actual.get(2).direction()).isEqualTo(Direction.INCREASE);
    var totals = service.totals(user, query(null, 1, 1));
    assertThat(totals.frozen()).isEqualTo(money("25"));
    assertThat(totals.available()).isEqualTo(money("40"));
    assertThat(totals.withdrawn()).isEqualTo(money("30"));
    assertThat(totals.commission()).isEqualTo(money("95"));
    assertThat(totals.prepaid()).isEqualTo(money("50"));
    assertThat(service.totals(user, query(1L, 1, 1)).prepaid()).isNull();
    assertThat(service.list(user, query(1L, 2, 3)).items())
        .extracting(FundFlow::status)
        .containsExactly(Status.AVAILABLE, Status.FROZEN);
  }

  @Test
  void allocatesWalletAcrossShopsBeforeFilteringAndUsesHalfOpenSnapshotDates() {
    when(flows.snapshots(TENANT, CREATOR))
        .thenReturn(
            List.of(
                item(1, 1, 1, FROM.minusSeconds(1), "80", "80"),
                item(2, 2, 2, FROM, "90", "80"),
                item(3, 3, 2, UNTIL, "5", "0")));
    when(wallet.findBalance(CREATOR)).thenReturn(money("100"));
    when(funding.listReleasedRows(CREATOR))
        .thenReturn(List.of(new FundingRow(1, 1, money("80")), new FundingRow(2, 2, money("80"))));

    var actual = service.list(user, query(2L, 1, 20)).items();

    assertThat(actual).extracting(FundFlow::orderNo).containsExactly("O2", "O2");
    assertThat(actual).extracting(FundFlow::amount).containsExactly(money("20"), money("10"));
    verifyNoInteractions(finance);
  }

  @Test
  void restrictsOwnScopeAndRejectsAnUnknownShopBeforeReadingBalances() {
    when(user.ownScope()).thenReturn(true);
    when(user.id()).thenReturn(99L);
    assertThatThrownBy(() -> service.list(user, query(null, 1, 20)))
        .isInstanceOf(BusinessException.class);
    when(user.id()).thenReturn(OWNER);
    assertThatThrownBy(() -> service.list(user, query(99L, 1, 20)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(flows, wallet, finance);
  }

  private static Query query(Long shop, int page, int size) {
    return new Query(CREATOR, shop, FROM, UNTIL, page, size);
  }

  private static Snapshot item(
      long id, long order, long shop, Instant time, String accrued, String released) {
    return new Snapshot(
        id, id, order, "O" + order, shop, OWNER, time, money(accrued), money(released));
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value).setScale(2);
  }
}
