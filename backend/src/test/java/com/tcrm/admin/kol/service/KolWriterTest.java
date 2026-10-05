package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.mapper.KolBdHistoryMapper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolBdHistoryDO;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class KolWriterTest {
  private final KolMapper kols = mock(KolMapper.class);
  private final KolBdHistoryMapper history = mock(KolBdHistoryMapper.class);
  private final KolCodeGenerator codes = mock(KolCodeGenerator.class);
  private final KolWriter writer =
      new KolWriter(
          kols, history, codes, new TransactionTemplate(mock(PlatformTransactionManager.class)));

  @BeforeEach
  void ids() {
    when(codes.next()).thenReturn("KOL0001", "KOL0002", "KOL0003", "KOL0004");
  }

  private static DuplicateKeyException duplicate(String constraint) {
    return new DuplicateKeyException(
        "unit",
        new SQLException(
            "ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
  }

  private static KolDO kol(Long bd) {
    KolDO kol = new KolDO();
    kol.setEmail("a@example.com");
    kol.setBdUserId(bd);
    return kol;
  }

  @Test
  void shouldInsertKolAndFirstPeriod_whenBdIsAssigned() {
    doAnswer(
            i -> {
              i.<KolDO>getArgument(0).setId(11L);
              return 1;
            })
        .when(kols)
        .insert(any(KolDO.class));
    KolDO saved = writer.insert(kol(5L), null, 1L);
    assertThat(saved.getKolCode()).isEqualTo("KOL0001");
    assertThat(saved.getCreateBy()).isEqualTo(1L);
    assertThat(saved.getDeleted()).isFalse();
    var period = ArgumentCaptor.forClass(KolBdHistoryDO.class);
    verify(history).insert(period.capture());
    assertThat(period.getValue().getKolId()).isEqualTo(11L);
    assertThat(period.getValue().getBdUserId()).isEqualTo(5L);
    assertThat(period.getValue().getStartTime()).isNotNull();
    assertThat(period.getValue().getEndTime()).isNull();
    assertThat(period.getValue().getCreateBy()).isEqualTo(1L);
  }

  @Test
  void shouldNotWriteHistory_whenKolIsUnassigned() {
    writer.insert(kol(null), "KOL0100", 1L);
    verify(history, never()).insert(any(KolBdHistoryDO.class));
    verify(codes, never()).next();
  }

  @Test
  void shouldRetryWithNewCode_whenGeneratedCodeLosesRace() {
    when(kols.insert(any(KolDO.class))).thenThrow(duplicate("uk_crm_kol_kol_code")).thenReturn(1);
    assertThat(writer.insert(kol(null), null, 1L).getKolCode()).isEqualTo("KOL0002");
  }

  @Test
  void shouldReportCodeExists_afterThreeLostRaces() {
    when(kols.insert(any(KolDO.class))).thenThrow(duplicate("uk_crm_kol_kol_code"));
    assertThatThrownBy(() -> writer.insert(kol(null), null, 1L))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.KOL_CODE_EXISTS.message());
    verify(kols, times(KolWriter.MAX_ATTEMPTS)).insert(any(KolDO.class));
  }

  @Test
  void shouldReportCodeExists_whenRequestedCodeIsTaken() {
    when(kols.insert(any(KolDO.class))).thenThrow(duplicate("uk_crm_kol_kol_code"));
    assertThatThrownBy(() -> writer.insert(kol(null), "KOL0100", 1L))
        .hasMessage(ErrorCode.KOL_CODE_EXISTS.message());
    verify(kols, times(1)).insert(any(KolDO.class));
  }

  @Test
  void shouldReportEmailExists_whenEmailIndexIsViolated() {
    when(kols.insert(any(KolDO.class))).thenThrow(duplicate("uk_crm_kol_email"));
    assertThatThrownBy(() -> writer.insert(kol(null), null, 1L))
        .hasMessage(ErrorCode.KOL_EMAIL_EXISTS.message());
  }

  @Test
  void shouldReportConflict_whenAnotherUniqueIndexIsViolated() {
    when(kols.insert(any(KolDO.class))).thenReturn(1);
    when(history.insert(any(KolBdHistoryDO.class)))
        .thenThrow(duplicate("uk_crm_kol_bd_history_current"));
    assertThatThrownBy(() -> writer.insert(kol(5L), null, 1L))
        .hasMessage(ErrorCode.COMMON_CONFLICT.message());
  }
}
