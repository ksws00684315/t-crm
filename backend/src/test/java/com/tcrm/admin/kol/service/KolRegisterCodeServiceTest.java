package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolRegisterCodeMapper;
import com.tcrm.admin.kol.model.KolRegisterCodeDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import com.tcrm.admin.system.api.BdUserApi;
import com.tcrm.admin.system.api.BdUserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class KolRegisterCodeServiceTest {
  private final KolRegisterCodeMapper codes = mock(KolRegisterCodeMapper.class);
  private final BdUserApi bdUsers = mock(BdUserApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final KolRegisterCodeService service =
      new KolRegisterCodeService(
          codes,
          bdUsers,
          current,
          new KolPortalUrls("https://kol.example.com"),
          TestTransactions.template());

  @BeforeEach
  void setUp() {
    when(current.user()).thenReturn(account(8L));
    when(bdUsers.getById(8L)).thenReturn(new BdUserDTO(8L, "bd", "BD", true));
    when(codes.lockByBd(8L)).thenReturn(java.util.List.of());
  }

  @Test
  void shouldCreateACode_whenTheBdHasNone() {
    when(codes.selectOne(any())).thenReturn(null);
    KolRegisterCodeDTO dto = service.current();
    assertThat(dto.code()).matches("[2-9A-HJ-NP-Z]{8}");
    assertThat(dto.url()).isEqualTo("https://kol.example.com/register?code=" + dto.code());
    assertThat(dto.status()).isEqualTo(1);
    ArgumentCaptor<KolRegisterCodeDO> inserted = ArgumentCaptor.forClass(KolRegisterCodeDO.class);
    verify(codes).insert(inserted.capture());
    assertThat(inserted.getValue().getBdUserId()).isEqualTo(8L);
    assertThat(inserted.getValue().getTenantId()).isNull();
    assertThat(inserted.getValue().getCreateBy()).isEqualTo(8L);
    assertThat(inserted.getValue().getStatus()).isEqualTo(KolRegisterCodeDO.STATUS_ACTIVE);
  }

  @Test
  void shouldReturnTheExistingCode_withoutInserting() {
    KolRegisterCodeDO active = row("A2B3C4D5");
    when(codes.selectOne(any())).thenReturn(active);
    assertThat(service.current().code()).isEqualTo(active.getCode());
    verify(codes, never()).insert(any(KolRegisterCodeDO.class));
  }

  @Test
  void shouldReturnTheWinner_whenAConcurrentInsertWinsTheUniqueIndex() {
    KolRegisterCodeDO winner = row("B2C3D4E5");
    when(codes.selectOne(any())).thenReturn(null).thenReturn(winner);
    doThrow(new DuplicateKeyException("uk_crm_kol_register_code_active_bd"))
        .when(codes)
        .insert(any(KolRegisterCodeDO.class));
    assertThat(service.current().code()).isEqualTo(winner.getCode());
    verify(codes, times(1)).insert(any(KolRegisterCodeDO.class));
  }

  @Test
  void shouldGiveUp_afterThreeLostRaces() {
    when(codes.selectOne(any())).thenReturn(null);
    doThrow(new DuplicateKeyException("uk_crm_kol_register_code_code"))
        .when(codes)
        .insert(any(KolRegisterCodeDO.class));
    assertThatThrownBy(service::current)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.COMMON_CONFLICT.message());
    verify(codes, times(KolRegisterCodeService.MAX_ATTEMPTS)).insert(any(KolRegisterCodeDO.class));
  }

  @Test
  void shouldRevokeThenInsert_whenRotating() {
    when(codes.selectOne(any())).thenReturn(null);
    KolRegisterCodeDTO dto = service.rotate();
    assertThat(dto.code()).matches("[2-9A-HJ-NP-Z]{8}");
    verify(codes).revokeActive(eq(8L), eq(8L), any());
    verify(codes).insert(any(KolRegisterCodeDO.class));
  }

  @Test
  void shouldRejectACallerTheBdPickerDoesNotTreatAsUsable() {
    when(bdUsers.getById(8L)).thenReturn(new BdUserDTO(8L, "bd", "BD", false));
    assertThatThrownBy(service::current)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    when(bdUsers.getById(8L)).thenReturn(null);
    assertThatThrownBy(service::rotate)
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_INVALID_BD_USER.message());
    verify(codes, never()).lockByBd(anyLong());
  }

  private static AccountDTO account(long id) {
    return new AccountDTO(
        id,
        "bd",
        "hash",
        "BD",
        null,
        Role.STAFF,
        DataScope.OWN,
        1,
        null,
        null,
        0,
        1L,
        "Acme",
        true);
  }

  private static KolRegisterCodeDO row(String code) {
    KolRegisterCodeDO row = new KolRegisterCodeDO();
    row.setId(1L);
    row.setBdUserId(8L);
    row.setCode(code);
    row.setStatus(KolRegisterCodeDO.STATUS_ACTIVE);
    return row;
  }
}
