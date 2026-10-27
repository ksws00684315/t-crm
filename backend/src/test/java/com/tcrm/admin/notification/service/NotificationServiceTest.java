package com.tcrm.admin.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.BdTransferCancelledEvent;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.notification.api.PayoutAccountSubmittedEvent;
import com.tcrm.admin.notification.api.WithdrawalNotification;
import com.tcrm.admin.notification.mapper.NotificationMapper;
import com.tcrm.admin.notification.model.NotificationDO;
import com.tcrm.admin.notification.model.NotificationType;
import com.tcrm.admin.order.api.OrderSyncFailedEvent;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.EnabledSuperAdminApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-17T03:04:05.678Z");
  private final NotificationMapper notifications = mock(NotificationMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final EnabledSuperAdminApi superAdmins = mock(EnabledSuperAdminApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final NotificationService service =
      new NotificationService(
          notifications,
          kols,
          superAdmins,
          current,
          TestTransactions.template(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeAll
  static void table() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationDO.class);
  }

  @BeforeEach
  void companyUser() {
    when(current.user()).thenReturn(account(8L, Role.STAFF, 2L));
  }

  @Test
  void shouldNotifyTheBdAndEverySuperAdmin_whenAPayoutAccountIsSubmitted() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(10L, "KOL0010", "Ada", true, 3L)));
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L, 2L));
    service.record(new PayoutAccountSubmittedEvent(9L, 10L, 77L, "KOL0010", "PAYPAL"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications, times(3)).insert(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(NotificationDO::getUserId)
        .containsExactly(3L, 1L, 2L);
    assertThat(rows.getAllValues())
        .allSatisfy(
            row -> {
              assertThat(row.getTenantId()).isEqualTo(9L);
              assertThat(row.getType()).isEqualTo(NotificationType.PAYOUT_ACCOUNT_SUBMITTED);
              assertThat(row.getBizType()).isEqualTo(NotificationService.BIZ_PAYOUT_ACCOUNT);
              assertThat(row.getBizId()).isEqualTo(77L);
              assertThat(row.getCreateTime())
                  .isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
              assertThat(row.getParams())
                  .isEqualTo(
                      "{\"kolCode\":\"KOL0010\",\"kolName\":\"Ada\",\"accountType\":\"PAYPAL\"}");
              assertThat(row.getParams()).doesNotContain("@");
            });
  }

  @Test
  void shouldNotifyOnlySuperAdmins_whenTheCreatorHasNoBd() {
    when(kols.listByIds(any()))
        .thenReturn(List.of(new KolRefDTO(10L, "KOL0010", "Ada", true, null)));
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L));
    service.record(new PayoutAccountSubmittedEvent(9L, 10L, 77L, "KOL0010", "USDT"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications).insert(rows.capture());
    assertThat(rows.getValue().getUserId()).isEqualTo(1L);
  }

  @Test
  void shouldKeepOnlyTheCode_whenTheCreatorNoLongerResolves() {
    when(kols.listByIds(any())).thenReturn(List.of());
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L));
    service.record(new PayoutAccountSubmittedEvent(9L, 10L, 77L, "KOL0010", "USDT"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications).insert(rows.capture());
    assertThat(rows.getValue().getParams())
        .isEqualTo("{\"kolCode\":\"KOL0010\",\"accountType\":\"USDT\"}");
  }

  @Test
  void shouldWriteCodeAndName_whenADepartureCancelsATransfer() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(10L, "KOL0010", "Ada", true, 3L)));
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L));
    service.record(new BdTransferCancelledEvent(9L, 10L, 55L, "KOL0010", "OWNERSHIP_CHANGED"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications).insert(rows.capture());
    assertThat(rows.getValue().getType()).isEqualTo(NotificationType.BD_TRANSFER_CANCELLED);
    assertThat(rows.getValue().getParams())
        .isEqualTo(
            "{\"kolCode\":\"KOL0010\",\"kolName\":\"Ada\",\"reason\":\"OWNERSHIP_CHANGED\"}");
  }

  @Test
  void shouldWriteCodeAndName_whenAWithdrawalEventIsDelivered() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(10L, "KOL0010", "Ada", true, 3L)));
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L));
    service.deliver(
        new WithdrawalNotification(9L, 100L, 10L, "KOL0010", "W20261017001", "SUBMITTED"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications, times(2)).insertWithdrawalEvent(rows.capture());
    assertThat(rows.getAllValues()).extracting(NotificationDO::getUserId).containsExactly(3L, 1L);
    assertThat(rows.getAllValues())
        .allSatisfy(
            row ->
                assertThat(row.getParams())
                    .isEqualTo(
                        "{\"kolCode\":\"KOL0010\",\"kolName\":\"Ada\","
                            + "\"applicationNo\":\"W20261017001\"}"));
  }

  @Test
  void shouldParseTheKolName_fromAStoredMessage() {
    when(notifications.selectPage(any(), any()))
        .thenAnswer(
            invocation -> {
              Page<NotificationDO> page = invocation.getArgument(0);
              NotificationDO row = new NotificationDO();
              row.setId(6L);
              row.setUserId(8L);
              row.setType(NotificationType.BD_TRANSFER_CANCELLED);
              row.setParams(
                  "{\"kolCode\":\"KOL0010\",\"kolName\":\"Ada\",\"reason\":\"OWNERSHIP_CHANGED\"}");
              row.setCreateTime(NOW);
              page.setRecords(List.of(row));
              page.setTotal(1);
              return page;
            });
    var item = service.page(1, 20, null).items().getFirst();
    assertThat(item.params().kolName()).isEqualTo("Ada");
    assertThat(item.params().kolCode()).isEqualTo("KOL0010");
  }

  @Test
  void shouldParseLegacyMessages_thatCarryOnlyTheCode() {
    when(notifications.selectPage(any(), any()))
        .thenAnswer(
            invocation -> {
              Page<NotificationDO> page = invocation.getArgument(0);
              NotificationDO row = new NotificationDO();
              row.setId(6L);
              row.setUserId(8L);
              row.setType(NotificationType.PAYOUT_ACCOUNT_SUBMITTED);
              row.setParams("{\"kolCode\":\"KOL0010\",\"accountType\":\"PAYPAL\"}");
              row.setCreateTime(NOW);
              page.setRecords(List.of(row));
              page.setTotal(1);
              return page;
            });
    var item = service.page(1, 20, null).items().getFirst();
    assertThat(item.params().kolName()).isNull();
    assertThat(item.params().kolCode()).isEqualTo("KOL0010");
    assertThat(item.params().accountType()).isEqualTo("PAYPAL");
  }

  @Test
  void shouldNotifyAPersonOnce_whenTheBdIsAlsoListedAsSuperAdmin() {
    when(kols.listByIds(any())).thenReturn(List.of(new KolRefDTO(10L, "KOL0010", "Ada", true, 1L)));
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L, 2L));
    service.record(new PayoutAccountSubmittedEvent(9L, 10L, 77L, "KOL0010", "PAYPAL"));
    var rows = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications, times(2)).insert(rows.capture());
    assertThat(rows.getAllValues()).extracting(NotificationDO::getUserId).containsExactly(1L, 2L);
  }

  @Test
  void shouldInsertNothing_whenNobodyCanReceiveTheMessage() {
    when(superAdmins.listEnabledIds()).thenReturn(List.of());
    service.record(new OrderSyncFailedEvent(9L, 3L, "Shop A"));
    verify(notifications, never()).insert(any(NotificationDO.class));
  }

  @Test
  void shouldWriteTheEventCompany_whenTheCallerIsInSystemScope() {
    when(superAdmins.listEnabledIds()).thenReturn(List.of(1L));
    when(notifications.insert(any(NotificationDO.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TenantScope.companyId()).isEqualTo(9L);
              return 1;
            });
    TenantScope.runSystem(
        "scheduled order sync", () -> service.record(new OrderSyncFailedEvent(9L, 3L, "Shop A")));
    var row = ArgumentCaptor.forClass(NotificationDO.class);
    verify(notifications).insert(row.capture());
    assertThat(row.getValue().getTenantId()).isEqualTo(9L);
    assertThat(row.getValue().getParams()).isEqualTo("{\"shopName\":\"Shop A\"}");
  }

  @Test
  void shouldCountAndPageOnlyTheCallersMessages() {
    when(notifications.selectCount(any())).thenReturn(2L);
    when(notifications.selectPage(any(), any()))
        .thenAnswer(
            invocation -> {
              Page<NotificationDO> page = invocation.getArgument(0);
              NotificationDO row = new NotificationDO();
              row.setId(5L);
              row.setUserId(8L);
              row.setType(NotificationType.ORDER_SYNC_FAILED);
              row.setParams("{\"shopName\":\"Shop A\"}");
              row.setCreateTime(NOW);
              page.setRecords(List.of(row));
              page.setTotal(1);
              return page;
            });
    assertThat(service.unreadCount()).isEqualTo(2L);
    assertThat(service.page(1, 20, true).items()).hasSize(1);
    assertThat(service.page(1, 20, true).items().getFirst().params().shopName())
        .isEqualTo("Shop A");
  }

  @Test
  void shouldRejectAPlatformAdministrator() {
    when(current.user()).thenReturn(account(1L, Role.PLATFORM_ADMIN, null));
    assertThatThrownBy(() -> service.unreadCount()).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    assertThatThrownBy(() -> service.page(1, 20, null))
        .hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
  }

  @Test
  void shouldReturnNotFound_whenTheMessageBelongsToSomeoneElse() {
    when(notifications.selectOne(any())).thenReturn(null);
    assertThatThrownBy(() -> service.markRead(5L)).hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    verify(notifications, never()).update(any(), any());
  }

  @Test
  void shouldLeaveAnAlreadyReadMessageUntouched() {
    NotificationDO row = new NotificationDO();
    row.setId(5L);
    row.setUserId(8L);
    row.setReadTime(NOW);
    when(notifications.selectOne(any())).thenReturn(row);
    service.markRead(5L);
    verify(notifications, never()).update(any(), any());
  }

  @Test
  void shouldFilterReadsByTheCaller() {
    when(notifications.selectCount(any())).thenReturn(0L);
    service.unreadCount();
    @SuppressWarnings("unchecked")
    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(notifications).selectCount(wrapper.capture());
    assertThat(wrapper.getValue().getSqlSegment()).contains("user_id");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs()
                .values())
        .contains(8L);
  }

  private static AccountDTO account(long id, Role role, Long tenantId) {
    return new AccountDTO(
        id,
        "user" + id,
        null,
        "User",
        null,
        role,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        tenantId,
        "Company",
        true);
  }
}
