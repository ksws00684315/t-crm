package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolPortalProfileServiceTest {
  @BeforeAll
  static void metadata() {
    for (var type : List.of(KolDO.class, KolPromotionRecordDO.class)) {
      TableInfoHelper.initTableInfo(
          new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }
  }

  private final KolMapper kols = mock(KolMapper.class);
  private final KolPromotionRecordMapper promotionRecords = mock(KolPromotionRecordMapper.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final ExternalAuditApi audit = mock(ExternalAuditApi.class);
  private final KolPortalProfileService service =
      new KolPortalProfileService(kols, promotionRecords, current, audit);

  @BeforeEach
  void setUp() {
    when(current.kol()).thenReturn(account());
    when(kols.lockById(700L)).thenReturn(700L);
    when(kols.selectById(700L)).thenReturn(kol());
    when(kols.update(isNull(), any())).thenReturn(1);
  }

  @Test
  void shouldExposePrimarySocialStatusesWithNullRateFields_whenNoRateIsConfigured() {
    KolPortalProfileDTO profile = service.get();

    assertThat(profile)
        .isEqualTo(
            new KolPortalProfileDTO(
                "KOL0700", "TikTok", "@alice", "APPROVED", "NORMAL", "PROMOTING", null, null));
  }

  @Test
  void shouldExposeTheDefaultRateAndItsLatestConfigEffectiveTime() {
    KolDO kol = kol();
    kol.setDefaultRate(new BigDecimal("0.1550"));
    when(kols.selectById(700L)).thenReturn(kol);
    Instant effective = Instant.parse("2026-10-01T00:00:00Z");
    KolPromotionRecordDO config = new KolPromotionRecordDO();
    config.setKolId(700L);
    config.setChangeType("CONFIG");
    config.setEffectiveTime(effective);
    when(promotionRecords.selectList(any())).thenReturn(List.of(config));

    KolPortalProfileDTO profile = service.get();

    assertThat(profile.commissionRate()).isEqualTo("15.50");
    assertThat(profile.rateEffectiveAt()).isEqualTo(effective);
  }

  @Test
  void shouldReadTheEffectiveTimeEvenWithoutADefaultRate() {
    KolDO kol = kol();
    kol.setDefaultRate(null);
    when(kols.selectById(700L)).thenReturn(kol);
    Instant effective = Instant.parse("2026-09-01T00:00:00Z");
    KolPromotionRecordDO config = new KolPromotionRecordDO();
    config.setKolId(700L);
    config.setChangeType("CONFIG");
    config.setEffectiveTime(effective);
    when(promotionRecords.selectList(any())).thenReturn(List.of(config));

    KolPortalProfileDTO profile = service.get();

    assertThat(profile.commissionRate()).isNull();
    assertThat(profile.rateEffectiveAt()).isEqualTo(effective);
  }

  @Test
  void shouldPreferFrozenOverAccountStatus_andReadNullarySocials() {
    KolDO kol = kol();
    kol.setStatus(0);
    when(kols.selectById(700L)).thenReturn(kol);
    assertThat(service.get().loginStatus()).isEqualTo("FROZEN");

    KolDO deactivated = kol();
    deactivated.setStatus(1);
    deactivated.setAccountStatus(0);
    deactivated.setSocialAccounts(null);
    when(kols.selectById(700L)).thenReturn(deactivated);
    KolPortalProfileDTO profile = service.get();
    assertThat(profile.loginStatus()).isEqualTo("ABNORMAL");
    assertThat(profile.primaryPlatform()).isNull();
    assertThat(profile.primaryHandle()).isNull();
  }

  @Test
  void shouldUpdateNameAndReplaceOnlyThePrimarySocialSlot() {
    service.update("  Alice Kim  ", " YouTube ", " @alice.kim ", "127.0.0.1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<KolDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), captor.capture());
    assertThat(captor.getValue().getSqlSegment()).contains("id =").contains("tenant_id =");
    assertThat(savedValues(captor)).anyMatch(v -> v.contains("Alice Kim"));
    assertThat(savedValues(captor))
        .anyMatch(
            v ->
                v.contains("\"platform\":\"YouTube\"")
                    && v.contains("\"handle\":\"@alice.kim\"")
                    && v.contains("\"handle\":\"@old.handle\"")
                    && !v.contains("\"platform\":\"TikTok\""));
    verify(audit)
        .recordKolOperation(
            eq(77L),
            eq("creator@example.com"),
            eq("KolProfile"),
            eq("Update profile"),
            eq("/kol-api/me"),
            eq("127.0.0.1"),
            eq("{\"kolId\":\"700\"}"));
  }

  @Test
  void shouldClearThePrimarySlot_whenPlatformAndHandleAreAbsent() {
    service.update("Alice", null, null, "127.0.0.1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Wrapper<KolDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(kols).update(isNull(), captor.capture());
    assertThat(savedValues(captor)).anyMatch(v -> v.contains("Alice"));
    assertThat(savedValues(captor))
        .anyMatch(
            v ->
                v.contains("\"handle\":\"@old.handle\"")
                    && !v.contains("@alice")
                    && !v.contains("\"primary\":true"));
  }

  private static List<String> savedValues(ArgumentCaptor<Wrapper<KolDO>> captor) {
    return params(captor.getValue()).values().stream().map(String::valueOf).toList();
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> params(Wrapper<KolDO> wrapper) {
    return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<Object, Object, ?>)
            (Object) wrapper)
        .getParamNameValuePairs();
  }

  @Test
  void shouldRejectBlankOrOversizedNames_andHalfSocialPairs() {
    List<Runnable> invalid =
        List.of(
            () -> service.update(null, null, null, "127.0.0.1"),
            () -> service.update("   ", null, null, "127.0.0.1"),
            () -> service.update("名".repeat(65), null, null, "127.0.0.1"),
            () -> service.update("Alice", "TikTok", null, "127.0.0.1"),
            () -> service.update("Alice", null, "@alice", "127.0.0.1"),
            () -> service.update("Alice", "p".repeat(33), "@alice", "127.0.0.1"),
            () -> service.update("Alice", "TikTok", "h".repeat(129), "127.0.0.1"));
    for (Runnable run : invalid) {
      assertThatThrownBy(run::run).isInstanceOf(BusinessException.class);
    }
    verifyNoInteractions(kols, audit);
  }

  @Test
  void shouldAnswerNotFound_whenTheRowLeavesTheSessionTenant() {
    KolDO moved = kol();
    moved.setTenantId(999L);
    when(kols.selectById(700L)).thenReturn(moved);

    assertThatThrownBy(() -> service.get()).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.update("Alice", null, null, "127.0.0.1"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void shouldAnswerNotFound_whenTheRowVanishesBeforeTheWrite() {
    when(kols.update(isNull(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.update("Alice", null, null, "127.0.0.1"))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(audit);
  }

  @Test
  void shouldAnswerNotFound_whenTheRowIsGoneForReads() {
    when(kols.selectById(700L)).thenReturn(null);
    assertThatThrownBy(() -> service.get()).isInstanceOf(BusinessException.class);
  }

  private static KolAccountDTO account() {
    return new KolAccountDTO(
        700L,
        "KOL0700",
        "Creator",
        "creator@example.com",
        "US",
        "hash",
        1,
        1,
        0,
        Instant.EPOCH,
        77L,
        "Company",
        true);
  }

  private static KolDO kol() {
    KolDO kol = new KolDO();
    kol.setId(700L);
    kol.setKolCode("KOL0700");
    kol.setName("Creator");
    kol.setEmail("creator@example.com");
    kol.setTenantId(77L);
    kol.setStatus(1);
    kol.setAccountStatus(1);
    kol.setReviewStatus("APPROVED");
    kol.setRelationStatus("PROMOTING");
    kol.setSocialAccounts(
        SocialAccountsJson.write(
            List.of(
                new SocialAccountDTO("TikTok", "@alice", null, null, true),
                new SocialAccountDTO("Instagram", "@old.handle", null, 1000L, false))));
    return kol;
  }
}
