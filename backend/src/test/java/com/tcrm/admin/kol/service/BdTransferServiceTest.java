package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolBdTransferMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.BdTransferStatus;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolBdTransferDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import com.tcrm.admin.system.api.ExternalAuditApi;
import com.tcrm.admin.system.api.StaffDisabledEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class BdTransferServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private final KolMapper kols = mock(KolMapper.class);
  private final KolBdHistoryMapper history = mock(KolBdHistoryMapper.class);
  private final KolBdTransferMapper transfers = mock(KolBdTransferMapper.class);
  private final BdUserApi bds = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final BdTransferService service =
      new BdTransferService(
          kols,
          history,
          transfers,
          bds,
          current,
          TestTransactions.template(),
          audit,
          new AuditSanitizer(new ObjectMapper()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeAll
  static void tables() {
    for (Class<?> type : List.of(KolDO.class, KolBdHistoryDO.class, KolBdTransferDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  @BeforeEach
  void actor() {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L,
                "admin",
                null,
                "Admin",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                1L,
                "Test",
                true));
    when(bds.lockById(6L)).thenReturn(new BdUserDTO(6L, "bd6", "BD", true));
    when(kols.lockById(11L)).thenReturn(11L);
    when(kols.selectById(11L)).thenReturn(kol(11L, 5L));
  }

  private static KolDO kol(Long id, Long bd) {
    var row = new KolDO();
    row.setId(id);
    row.setKolCode("KOL" + id);
    row.setBdUserId(bd);
    return row;
  }

  private static BdTransferCommand command(Instant effective) {
    return new BdTransferCommand(List.of(11L), null, 6L, effective, "handover");
  }

  private KolBdTransferDO reservation(Instant effective) {
    var r = new KolBdTransferDO();
    r.setId(21L);
    r.setKolId(11L);
    r.setFromBdUserId(5L);
    r.setToBdUserId(6L);
    r.setEffectiveAt(effective);
    r.setStatus(BdTransferStatus.SCHEDULED);
    when(transfers.selectById(21L)).thenReturn(r);
    return r;
  }

  @Test
  void shouldWriteDoneAndAudit_whenImmediatelyTransferred() {
    assertThat(service.create(command(null))).isEqualTo(new BdTransferResultDTO(1, 0, 0));
    var row = ArgumentCaptor.forClass(KolBdTransferDO.class);
    verify(transfers).insert(row.capture());
    assertThat(row.getValue().getFromBdUserId()).isEqualTo(5L);
    assertThat(row.getValue().getToBdUserId()).isEqualTo(6L);
    assertThat(row.getValue().getStatus()).isEqualTo(BdTransferStatus.DONE);
    assertThat(row.getValue().getExecutedAt()).isEqualTo(NOW);
    assertThat(row.getValue().getReason()).isEqualTo("handover");
    verify(history).closeCurrent(11L, NOW, 1L);
    var params = ArgumentCaptor.forClass(String.class);
    verify(audit)
        .recordTenantWriteOperation(
            eq(1L),
            eq(1L),
            eq("KOL"),
            eq("Create BD transfer"),
            eq("POST"),
            any(),
            params.capture());
    assertThat(params.getValue())
        .contains("\"kolCount\":1", "handover", "\"sourceBdUserIds\":[5]", "\"toBdUserId\":6");
  }

  @Test
  void shouldSkipWithoutCancelling_whenAlreadyAssignedToTarget() {
    when(kols.selectById(11L)).thenReturn(kol(11L, 6L));
    assertThat(service.create(command(NOW))).isEqualTo(new BdTransferResultDTO(0, 0, 1));
    verifyNoInteractions(history, transfers);
  }

  @Test
  void shouldSelectAllFromSource_whenSourceIsProvided() {
    when(bds.getById(5L)).thenReturn(new BdUserDTO(5L, "source", "Source", false));
    when(kols.selectList(any())).thenReturn(List.of(kol(11L, 5L)));
    assertThat(service.create(new BdTransferCommand(null, 5L, null, null, null)))
        .isEqualTo(new BdTransferResultDTO(1, 0, 0));
    var next = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(next.capture());
    assertThat(next.getValue().getBdUserId()).isNull();
  }

  @Test
  void shouldScheduleUnassignedTarget_whenFutureEffectiveTimeIsProvided() {
    var time = NOW.plusSeconds(3600);
    assertThat(service.create(new BdTransferCommand(List.of(11L), null, null, time, null)))
        .isEqualTo(new BdTransferResultDTO(0, 1, 0));
    var row = ArgumentCaptor.forClass(KolBdTransferDO.class);
    verify(transfers).insert(row.capture());
    assertThat(row.getValue().getStatus()).isEqualTo(BdTransferStatus.SCHEDULED);
    assertThat(row.getValue().getToBdUserId()).isNull();
    assertThat(row.getValue().getEffectiveAt()).isEqualTo(time);
    assertThat(row.getValue().getReason()).isNull();
    verifyNoInteractions(history);
  }

  @Test
  void shouldKeepReasonEmpty_whenLegacyPutTransfersOneKol() {
    service.updateOne(11L, 6L);
    var row = ArgumentCaptor.forClass(KolBdTransferDO.class);
    verify(transfers).insert(row.capture());
    assertThat(row.getValue().getStatus()).isEqualTo(BdTransferStatus.DONE);
    assertThat(row.getValue().getReason()).isNull();
  }

  @Test
  void shouldReportCodesWithoutWriting_whenReservationExists() {
    when(transfers.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> service.create(command(NOW.plusSeconds(1))))
        .isInstanceOfSatisfying(
            BdTransferExistsException.class,
            e -> assertThat(e.getKolCodes()).containsExactly("KOL11"));
    verify(transfers, never()).insert(any(KolBdTransferDO.class));
    verifyNoInteractions(history, audit);
  }

  @Test
  void shouldRejectInvalidSelectorsAndTime_whenCreating() {
    for (var c :
        List.of(
            new BdTransferCommand(null, null, null, null, null),
            new BdTransferCommand(List.of(11L), 5L, null, null, null),
            new BdTransferCommand(List.of(), null, null, null, null),
            command(NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant().plusMillis(1)))) {
      assertThatThrownBy(() -> service.create(c))
          .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
    }
    verifyNoInteractions(kols, transfers, history);
  }

  @Test
  void shouldAllowOneYearBoundary_whenScheduling() {
    assertThat(
            service
                .create(command(NOW.atZone(ZoneOffset.UTC).plusYears(1).toInstant()))
                .scheduled())
        .isEqualTo(1);
  }

  @Test
  void shouldDistinguishMissingAndUnusableTarget_whenValidating() {
    when(bds.lockById(6L)).thenReturn(null);
    assertThatThrownBy(() -> service.create(command(null)))
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
    when(bds.lockById(6L)).thenReturn(new BdUserDTO(6L, "x", "X", false));
    assertThatThrownBy(() -> service.create(command(null)))
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    verifyNoInteractions(history, transfers);
  }

  @Test
  void shouldUseEffectiveTime_whenExecutingLate() {
    Instant effective = NOW.minusSeconds(90);
    reservation(effective);
    service.executeDue(21L);
    verify(history).closeCurrent(11L, effective, null);
    var next = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(next.capture());
    assertThat(next.getValue().getStartTime()).isEqualTo(effective);
    assertThat(updateValues()).contains(BdTransferStatus.DONE, NOW).doesNotContain("目标商务不可用");
  }

  @Test
  void shouldClampTime_whenHistoryStartsAfterEffectiveTime() {
    reservation(NOW.minusSeconds(120));
    var period = new KolBdHistoryDO();
    period.setStartTime(NOW.minusSeconds(60));
    when(history.selectOne(any())).thenReturn(period);
    service.executeDue(21L);
    verify(history).closeCurrent(11L, period.getStartTime(), null);
    assertThat(updateValues()).contains(BdTransferStatus.DONE);
  }

  @Test
  void shouldCancelWithoutHistory_whenOwnershipHasChanged() {
    reservation(NOW.minusSeconds(1));
    when(kols.selectById(11L)).thenReturn(kol(11L, null));
    service.executeDue(21L);
    assertThat(updateValues()).contains("执行时归属已变化", BdTransferStatus.CANCELLED);
    verifyNoInteractions(history);
  }

  @Test
  void shouldCancelWithoutHistory_whenTargetIsUnavailable() {
    reservation(NOW.minusSeconds(1));
    when(bds.lockById(6L)).thenReturn(null);
    service.executeDue(21L);
    assertThat(updateValues()).contains("目标商务不可用", BdTransferStatus.CANCELLED);
    verifyNoInteractions(history);
  }

  @Test
  void shouldExecuteToCompany_whenTargetIsNull() {
    reservation(NOW.minusSeconds(1)).setToBdUserId(null);
    service.executeDue(21L);
    var next = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(next.capture());
    assertThat(next.getValue().getBdUserId()).isNull();
    verifyNoInteractions(bds);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DONE", "CANCELLED"})
  void shouldNotExecuteAgain_whenAlreadyTerminal(String state) {
    reservation(NOW.minusSeconds(1)).setStatus(BdTransferStatus.valueOf(state));
    service.executeDue(21L);
    verifyNoInteractions(history, bds);
    verify(transfers, never()).update(isNull(), any());
  }

  @Test
  void shouldNotExecute_whenNotYetDue() {
    reservation(NOW.plusSeconds(1));
    service.executeDue(21L);
    verifyNoInteractions(history);
    verify(transfers, never()).update(isNull(), any());
  }

  @Test
  void shouldCancelAndAudit_whenScheduled() {
    reservation(NOW.plusSeconds(1));
    when(transfers.update(isNull(), any())).thenReturn(1);
    service.cancel(21L);
    assertThat(updateValues()).contains("手动取消", BdTransferStatus.CANCELLED);
    verify(audit)
        .recordTenantWriteOperation(
            eq(1L), eq(1L), eq("KOL"), eq("Cancel BD transfer"), eq("DELETE"), any(), any());
  }

  @Test
  void shouldConflict_whenCancellingTerminalRecord() {
    reservation(NOW);
    when(transfers.update(isNull(), any())).thenReturn(0);
    assertThatThrownBy(() -> service.cancel(21L))
        .hasMessage(ErrorCode.KOL_TRANSFER_NOT_SCHEDULED.message());
  }

  @Test
  void shouldClearOwnershipAndPreserveSourceReservations_whenDisabled() {
    when(kols.selectList(any())).thenReturn(List.of(kol(11L, 5L)));
    service.onStaffDisabled(new StaffDisabledEvent(1L, 5L));
    var next = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(next.capture());
    assertThat(next.getValue().getBdUserId()).isNull();
    verify(history).closeCurrent(11L, NOW, 1L);
    verify(transfers, times(2)).update(isNull(), any());
    verify(audit)
        .recordTenantWriteOperation(
            eq(1L),
            eq(1L),
            eq("KOL"),
            eq("Unassign disabled staff KOLs"),
            eq("SYSTEM"),
            any(),
            eq("{\"disabledUserId\":5,\"kolCount\":1}"));
  }

  @Test
  void shouldResolveDisplayFieldsWithFixedQueries_whenListing() {
    var done = new KolBdTransferDO();
    done.setId(22L);
    done.setKolId(11L);
    done.setFromBdUserId(5L);
    done.setToBdUserId(6L);
    done.setEffectiveAt(NOW);
    done.setStatus(BdTransferStatus.DONE);
    done.setExecutedAt(NOW);
    done.setReason("handover");
    var unassigned = new KolBdTransferDO();
    unassigned.setId(23L);
    unassigned.setKolId(11L);
    unassigned.setEffectiveAt(NOW);
    unassigned.setStatus(BdTransferStatus.SCHEDULED);
    when(transfers.selectPage(any(), any()))
        .thenAnswer(
            i -> {
              Page<KolBdTransferDO> p = i.getArgument(0);
              p.setRecords(List.of(done, unassigned));
              p.setTotal(2);
              return p;
            });
    var kol = new KolDO();
    kol.setId(11L);
    kol.setKolCode("KOL0011");
    kol.setName("Alice");
    when(kols.selectList(any())).thenReturn(List.of(kol));
    when(bds.listByIds(any()))
        .thenReturn(
            List.of(
                new BdUserDTO(5L, "source-login", "Source Nick", false),
                new BdUserDTO(6L, "target-login", "  ", false)));
    var result = service.page(new BdTransferFilterDTO(null, null, 5L, 6L, 1, 20));
    var first = result.items().get(0);
    assertThat(first.id()).isEqualTo(22L);
    assertThat(first.kolCode()).isEqualTo("KOL0011");
    assertThat(first.kolName()).isEqualTo("Alice");
    assertThat(first.fromBdUserName()).isEqualTo("Source Nick");
    assertThat(first.toBdUserName()).isEqualTo("target-login");
    assertThat(first.reason()).isEqualTo("handover");
    var second = result.items().get(1);
    assertThat(second.fromBdUserName()).isNull();
    assertThat(second.toBdUserName()).isNull();
    verify(kols, times(1)).selectList(any());
    verify(bds, times(1)).listByIds(any());
  }

  @Test
  void shouldSkipDisplayQueries_whenPageIsEmpty() {
    when(transfers.selectPage(any(), any()))
        .thenAnswer(
            i -> {
              Page<KolBdTransferDO> p = i.getArgument(0);
              p.setRecords(List.of());
              p.setTotal(0);
              return p;
            });
    var result = service.page(new BdTransferFilterDTO(null, null, null, null, 1, 20));
    assertThat(result.items()).isEmpty();
    verify(kols, never()).selectList(any());
    verify(bds, never()).listByIds(any());
  }

  @Test
  void shouldReturnEmpty_whenCodeIsNotInCompany() {
    var result = service.page(new BdTransferFilterDTO(null, "KOL0001", null, null, 1, 20));
    assertThat(result.items()).isEmpty();
    verifyNoInteractions(transfers);
  }

  @Test
  void shouldContinueBatch_whenOneExecutionFails() {
    var row = reservation(NOW);
    when(transfers.selectList(any())).thenReturn(List.of(row, row));
    when(kols.lockById(11L)).thenThrow(new IllegalStateException("test"));
    service.executeDueBatch();
    verify(kols, times(2)).lockById(11L);
  }

  @Test
  void shouldExecuteImmediatelyWithCurrentTime_whenRequestedTimeIsPast() {
    assertThat(service.create(command(NOW.minusSeconds(60))).immediate()).isEqualTo(1);
    var row = ArgumentCaptor.forClass(KolBdTransferDO.class);
    verify(transfers).insert(row.capture());
    assertThat(row.getValue().getEffectiveAt()).isEqualTo(NOW);
  }

  @Test
  void shouldRejectOversizedBatch_whenMoreThanFiveHundredIdsAreProvided() {
    var c = new BdTransferCommand(java.util.Collections.nCopies(501, 11L), null, 6L, null, null);
    assertThatThrownBy(() -> service.create(c))
        .hasMessage(ErrorCode.COMMON_VALIDATION_FAILED.message());
    verifyNoInteractions(kols, history, transfers);
  }

  @SuppressWarnings("unchecked")
  private java.util.Collection<Object> updateValues() {
    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(transfers).update(isNull(), wrapper.capture());
    return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
        .getParamNameValuePairs()
        .values();
  }
}
