package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KolPortalProfileServiceTest {
  @BeforeAll
  static void metadata() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
  }

  private final KolMapper kols = mock(KolMapper.class);
  private final CurrentKolContext current = mock(CurrentKolContext.class);
  private final KolPortalProfileService service = new KolPortalProfileService(kols, current);
  private KolDO row;

  @BeforeEach
  void setUp() {
    row = new KolDO();
    row.setId(21L);
    row.setTenantId(7L);
    row.setKolCode("KOL-ABC123");
    row.setName("Old name");
    row.setStatus(1);
    row.setAccountStatus(1);
    row.setReviewStatus("APPROVED");
    row.setRelationStatus("PROMOTING");
    row.setSocialAccounts(
        SocialAccountsJson.write(
            java.util.List.of(
                new SocialAccountDTO("Instagram", "@old", null, null, true),
                new SocialAccountDTO("YouTube", "channel-2", null, null, false))));
    when(current.kol()).thenReturn(account());
    when(kols.selectById(21L)).thenReturn(row);
    when(kols.lockById(21L)).thenReturn(21L);
    when(kols.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
  }

  @Test
  void returnsOnlyPublicProfileAndUsesTheExistingPrimarySocialSlot() {
    KolPortalProfileDTO profile = service.get();

    assertThat(profile.systemId()).isEqualTo("KOL-ABC123");
    assertThat(profile.primaryPlatform()).isEqualTo("Instagram");
    assertThat(profile.primaryHandle()).isEqualTo("@old");
    assertThat(profile.reviewStatus()).isEqualTo("APPROVED");
    assertThat(profile.relationStatus()).isEqualTo("PROMOTING");
    assertThat(profile.commissionRate()).isNull();

    service.update("New name", "TikTok", "@new");

    ArgumentCaptor<LambdaUpdateWrapper<KolDO>> update =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(kols).update(isNull(), update.capture());
    assertThat(update.getValue().getParamNameValuePairs().values().toString())
        .contains("New name", "TikTok", "@new", "YouTube", "channel-2")
        .doesNotContain("commissionRate", "email", "tenantName");
  }

  @Test
  void rejectsIncompletePrimaryPairBeforeWriting() {
    assertThatThrownBy(() -> service.update("New name", "TikTok", null))
        .isInstanceOf(BusinessException.class);

    verify(kols, never()).update(isNull(), any(LambdaUpdateWrapper.class));
  }

  private static KolAccountDTO account() {
    return new KolAccountDTO(
        21L,
        "KOL-ABC123",
        "Old name",
        "creator@example.test",
        "US",
        "hash",
        1,
        1,
        0,
        null,
        7L,
        "Company",
        true,
        null,
        null,
        null);
  }
}
