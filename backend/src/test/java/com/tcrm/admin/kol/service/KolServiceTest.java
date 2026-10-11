package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

class KolServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolBdHistoryDO.class);
  }

  private static final long BD = 5L;
  private static final long OTHER_BD = 6L;

  private final KolMapper kols = mock(KolMapper.class);
  private final KolBdHistoryMapper history = mock(KolBdHistoryMapper.class);
  private final KolWriter writer = mock(KolWriter.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final KolService service =
      new KolService(kols, history, writer, bdUsers, current, TestTransactions.template(), events);

  private void actAs(long id, Role role, DataScope scope) {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                id, "u" + id, null, "U", null, role, scope, 1, null, null, 0, null, null, true));
  }

  private static KolDO kol(Long bd) {
    KolDO kol = new KolDO();
    kol.setId(11L);
    kol.setKolCode("KOL0001");
    kol.setName("Alice");
    kol.setEmail("alice@example.com");
    kol.setCountry("US");
    kol.setSocialAccounts("[]");
    kol.setBdUserId(bd);
    kol.setStatus(1);
    return kol;
  }

  @SuppressWarnings("unchecked")
  private String pageQuery(KolFilterDTO filter) {
    when(kols.selectPage(any(), any()))
        .thenAnswer(
            i -> {
              Page<KolDO> page = i.getArgument(0);
              page.setRecords(List.of(kol(BD), kol(null)));
              page.setTotal(2);
              return page;
            });
    when(bdUsers.listByIds(any())).thenReturn(List.of(new BdUserDTO(BD, "bd5", "BD", true)));
    var result = service.page(filter);
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.items()).extracting(KolDTO::bdUserName).containsExactly("bd5", null);
    ArgumentCaptor<Wrapper<KolDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).selectPage(any(), query.capture());
    String sql = query.getValue().getSqlSegment();
    return sql + " " + paramValues(query.getValue());
  }

  private static String paramValues(Wrapper<KolDO> query) {
    var w = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query;
    return String.valueOf(w.getParamNameValuePairs().values());
  }

  @Test
  void shouldForceOwnBdFilter_whenBdListsWithAnotherBdUserId() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    String query = pageQuery(new KolFilterDTO(null, null, null, OTHER_BD, 1, 20));
    assertThat(query).contains("bd_user_id =").contains("[" + BD + "]").doesNotContain("6");
  }

  @Test
  void shouldApplyAllFilters_whenOperatorWithAllScopeLists() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    String query = pageQuery(new KolFilterDTO(" 50%_\\a ", "us", 1, OTHER_BD, 2, 10));
    assertThat(query)
        .contains("(kol_code ILIKE", "OR name ILIKE", "OR email ILIKE")
        .contains("country =", "status =", "bd_user_id =")
        .contains("ORDER BY create_time DESC,id DESC")
        .contains("%50\\%\\_\\\\a%", "US", "6");
  }

  @Test
  void shouldNotFilterByBd_whenSuperAdminOmitsBdUserId() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    String query = pageQuery(new KolFilterDTO("", " ", null, null, 1, 20));
    assertThat(query).doesNotContain("bd_user_id", "ILIKE", "country", "status");
  }

  @Test
  void shouldEscapeLikeWildcards() {
    assertThat(KolService.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
  }

  @Test
  void shouldReturnDetailWithHistory_whenBdOwnsKol() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    Instant t1 = Instant.parse("2026-10-01T00:00:00Z");
    Instant t2 = Instant.parse("2026-10-02T00:00:00Z");
    var current = new KolBdHistoryDO();
    current.setBdUserId(BD);
    current.setStartTime(t2);
    var previous = new KolBdHistoryDO();
    previous.setBdUserId(null);
    previous.setStartTime(t1);
    previous.setEndTime(t2);
    when(history.selectList(any())).thenReturn(List.of(current, previous));
    when(bdUsers.listByIds(any())).thenReturn(List.of(new BdUserDTO(BD, "bd5", "BD", false)));

    var detail = service.get(11L);

    assertThat(detail.kol().bdUserName()).isEqualTo("bd5");
    assertThat(detail.bdHistory())
        .containsExactly(
            new BdHistoryDTO(BD, "bd5", t2, null), new BdHistoryDTO(null, null, t1, t2));
  }

  @Test
  void shouldReturn403_whenBdReadsAnotherBdsKol() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    when(kols.selectById(11L)).thenReturn(kol(OTHER_BD));
    assertThatThrownBy(() -> service.get(11L)).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    when(kols.selectById(11L)).thenReturn(kol(null));
    assertThatThrownBy(() -> service.get(11L)).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
  }

  @Test
  void shouldReturn404_whenKolIsMissing() {
    actAs(1L, Role.FINANCE, DataScope.ALL);
    assertThatThrownBy(() -> service.get(11L)).hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  private static CreateKolCommand create(Long bd) {
    return new CreateKolCommand(
        " Alice ",
        " Alice@Example.COM ",
        "us",
        List.of(new SocialAccountDTO("TikTok", "@alice", null, 10L)),
        bd,
        new BigDecimal("0.1550"),
        " ");
  }

  @Test
  void shouldAssignBdToSelfAndIgnoreRequest_whenBdCreates() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    var result = service.create(create(OTHER_BD));
    var saved = ArgumentCaptor.forClass(KolDO.class);
    verify(writer).insert(saved.capture(), isNull(), eq(BD));
    assertThat(saved.getValue().getBdUserId()).isEqualTo(BD);
    assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
    assertThat(saved.getValue().getName()).isEqualTo("Alice");
    assertThat(saved.getValue().getCountry()).isEqualTo("US");
    assertThat(saved.getValue().getRemark()).isNull();
    assertThat(saved.getValue().getStatus()).isEqualTo(1);
    assertThat(saved.getValue().getSocialAccounts()).contains("\"followers\":10");
    assertThat(result.defaultRate()).isEqualByComparingTo("0.155");
    verify(bdUsers, never()).getById(anyLong());
  }

  @Test
  void shouldCreateUnassignedKol_whenAdminLeavesBdEmpty() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    KolDO stored = kol(null);
    when(kols.selectById(any())).thenReturn(stored);
    assertThat(service.create(create(null)).kolCode()).isEqualTo("KOL0001");
    var saved = ArgumentCaptor.forClass(KolDO.class);
    verify(writer).insert(saved.capture(), isNull(), eq(1L));
    assertThat(saved.getValue().getBdUserId()).isNull();
  }

  @Test
  void shouldAssignRequestedBd_whenBdIsUsable() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(bdUsers.getById(BD)).thenReturn(new BdUserDTO(BD, "bd5", "BD", true));
    when(bdUsers.listByIds(any())).thenReturn(List.of(new BdUserDTO(BD, "bd5", "BD", true)));
    assertThat(service.create(create(BD)).bdUserName()).isEqualTo("bd5");
  }

  @Test
  void shouldReject422_whenRequestedBdIsMissingOrNotUsable() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    assertThatThrownBy(() -> service.create(create(BD)))
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    when(bdUsers.getById(BD)).thenReturn(new BdUserDTO(BD, "bd5", "BD", false));
    assertThatThrownBy(() -> service.create(create(BD)))
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    verifyNoInteractions(writer);
  }

  @Test
  void shouldReject409_whenEmailBelongsToLiveKol() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> service.create(create(null)))
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
    verifyNoInteractions(writer);
  }

  @ParameterizedTest
  @CsvSource({"create", "update", "status", "bd"})
  void shouldReturn403_whenFinanceWrites(String operation) {
    actAs(1L, Role.FINANCE, DataScope.ALL);
    assertThatThrownBy(() -> write(operation)).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    verifyNoInteractions(kols, writer, history);
  }

  @ParameterizedTest
  @CsvSource({"update", "status"})
  void shouldReturn403_whenBdWritesAnotherBdsKol(String operation) {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    when(kols.selectById(11L)).thenReturn(kol(OTHER_BD));
    assertThatThrownBy(() -> write(operation)).hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    verify(kols, never()).updateById(any(KolDO.class));
  }

  private void write(String operation) {
    switch (operation) {
      case "create" -> service.create(create(null));
      case "update" -> service.update(11L, edit());
      case "status" -> service.updateStatus(11L, 0);
      default -> service.updateBdUser(11L, BD);
    }
  }

  private static EditKolCommand edit() {
    return new EditKolCommand("Alice B", "ALICE@example.com", "gb", List.of(), null, "VIP");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRejectBdEdit_whenKolLeftTheirScopeBeforeTheUpdate() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    // Transferred to another BD after the visibility check: the scoped UPDATE matches no row.
    when(kols.update(isNull(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.update(11L, edit()))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    ArgumentCaptor<Wrapper<KolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    assertThat(update.getValue().getSqlSegment()).contains("bd_user_id");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldUpdateEditableFieldsOnly_whenBdEditsOwnKol() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    KolDO kol = kol(BD);
    kol.setDefaultRate(new BigDecimal("0.1"));
    when(kols.selectById(11L)).thenReturn(kol);
    when(kols.update(isNull(), any())).thenReturn(1);

    service.update(11L, edit());

    ArgumentCaptor<Wrapper<KolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    verify(kols, never()).updateById(any(KolDO.class));
    var sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                update.getValue())
            .getSqlSet();
    assertThat(sqlSet)
        .contains("name=", "email=", "country=", "social_accounts=", "default_rate=", "remark=")
        .doesNotContain("bd_user_id")
        .doesNotContain("kol_code");
    // update_by is the actor (BD); bd_user_id must stay out of the SET clause above.
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update.getValue())
                .getParamNameValuePairs()
                .values())
        .contains("Alice B", "alice@example.com", "GB", "VIP");
    ArgumentCaptor<Wrapper<KolDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).selectCount(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("lower(email) =", "id <>");
  }

  @Test
  void shouldReject409_whenEditedEmailIsTaken() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.selectById(11L)).thenReturn(kol(null));
    when(kols.selectCount(any())).thenReturn(1L);
    assertThatThrownBy(() -> service.update(11L, edit()))
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
    when(kols.selectCount(any())).thenReturn(0L);
    when(kols.update(isNull(), any())).thenThrow(new DuplicateKeyException("unit"));
    assertThatThrownBy(() -> service.update(11L, edit()))
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldChangeStatus_whenAdminDisablesKol() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(kols.selectById(11L)).thenReturn(kol(OTHER_BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateStatus(11L, 0);

    ArgumentCaptor<Wrapper<KolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    verify(kols, never()).updateById(any(KolDO.class));
    var sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                update.getValue())
            .getSqlSet();
    assertThat(sqlSet).contains("status=").doesNotContain("bd_user_id");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update.getValue())
                .getParamNameValuePairs()
                .values())
        .contains(0, 1L);
  }

  @Test
  void shouldReturn403_whenBdChangesBd() {
    actAs(BD, Role.OPERATOR, DataScope.OWN);
    assertThatThrownBy(() -> service.updateBdUser(11L, null))
        .hasMessage(ErrorCode.AUTH_FORBIDDEN.message());
    verifyNoInteractions(kols, history);
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldClosePeriodAndOpenNewOne_whenBdChanges() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(bdUsers.getById(OTHER_BD)).thenReturn(new BdUserDTO(OTHER_BD, "bd6", "BD", true));
    when(kols.lockById(11L)).thenReturn(11L);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateBdUser(11L, OTHER_BD);

    ArgumentCaptor<Wrapper<KolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    verify(kols, never()).updateById(any(KolDO.class));
    var sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                update.getValue())
            .getSqlSet();
    assertThat(sqlSet).contains("bd_user_id=").doesNotContain("name=").doesNotContain("email=");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) update.getValue())
                .getParamNameValuePairs()
                .values())
        .contains(OTHER_BD);
    var closedAt = ArgumentCaptor.forClass(Instant.class);
    verify(history).closeCurrent(eq(11L), closedAt.capture(), eq(1L));
    var period = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(period.capture());
    assertThat(period.getValue().getBdUserId()).isEqualTo(OTHER_BD);
    assertThat(period.getValue().getStartTime()).isEqualTo(closedAt.getValue());
    assertThat(period.getValue().getEndTime()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldRecordUnassignedPeriod_whenBdIsCleared() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(kols.lockById(11L)).thenReturn(11L);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateBdUser(11L, null);

    ArgumentCaptor<Wrapper<KolDO>> update = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), update.capture());
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                    update.getValue())
                .getSqlSet())
        .contains("bd_user_id=");
    var period = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(period.capture());
    assertThat(period.getValue().getBdUserId()).isNull();
    verify(bdUsers, never()).getById(any());
  }

  @Test
  void shouldDoNothing_whenBdIsUnchanged() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(bdUsers.getById(BD)).thenReturn(new BdUserDTO(BD, "bd5", "BD", true));
    when(kols.lockById(11L)).thenReturn(11L);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    service.updateBdUser(11L, BD);
    verify(kols, never()).update(isNull(), any());
    verify(kols, never()).updateById(any(KolDO.class));
    verifyNoInteractions(history);
  }

  @Test
  void shouldReturn404_whenChangingBdOfMissingKol() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.lockById(11L)).thenReturn(null);
    assertThatThrownBy(() -> service.updateBdUser(11L, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_NOT_FOUND.message());
  }

  @Test
  void shouldReject422_whenNewBdIsNotUsable() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(bdUsers.getById(OTHER_BD)).thenReturn(new BdUserDTO(OTHER_BD, "x", "X", false));
    assertThatThrownBy(() -> service.updateBdUser(11L, OTHER_BD))
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    verifyNoInteractions(history);
  }

  /**
   * Regression for the race where edit/status used updateById on a stale row and overwrote a
   * concurrent updateBdUser. Edit and status must update only their own columns and never set
   * bd_user_id (architecture review on PR #20).
   */
  @Test
  @SuppressWarnings("unchecked")
  void shouldNotWriteBdUserId_whenEditingKol() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.selectById(11L)).thenReturn(kol(BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.update(11L, edit());

    ArgumentCaptor<Wrapper<KolDO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), wrapper.capture());
    verify(kols, never()).updateById(any(KolDO.class));
    String sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                wrapper.getValue())
            .getSqlSet();
    assertThat(sqlSet)
        .contains("name=", "email=", "country=", "social_accounts=", "default_rate=", "remark=")
        .doesNotContain("bd_user_id");
    assertThat(wrapper.getValue().getSqlSegment()).contains("id =");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs()
                .values())
        .contains("Alice B", "alice@example.com", "GB", "VIP");
  }

  @Test
  void shouldPublishStatusEvent_whenStatusChanges() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(kols.selectById(11L)).thenReturn(kol(OTHER_BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateStatus(11L, 0);

    verify(events).publishEvent(new KolStatusChangedEvent(11L));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldNotWriteBdUserId_whenChangingStatus() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(kols.selectById(11L)).thenReturn(kol(OTHER_BD));
    when(kols.update(isNull(), any())).thenReturn(1);

    service.updateStatus(11L, 0);

    ArgumentCaptor<Wrapper<KolDO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), wrapper.capture());
    verify(kols, never()).updateById(any(KolDO.class));
    String sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KolDO>)
                wrapper.getValue())
            .getSqlSet();
    assertThat(sqlSet).contains("status=").doesNotContain("bd_user_id");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs()
                .values())
        .contains(0);
  }
}
