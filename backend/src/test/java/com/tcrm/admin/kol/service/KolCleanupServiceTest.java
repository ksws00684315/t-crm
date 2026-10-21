package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.PromotingKolDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.KolPromotionRecordMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.KolPromotionRecordDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.AuditSanitizer;
import com.tcrm.admin.system.api.ExternalAuditApi;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class KolCleanupServiceTest {
  @BeforeAll
  static void initTableInfo() {
    // Lambda columns in the captured wrappers need the entity metadata that MyBatis
    // registers when mappers load; a plain Mockito test has to provide it itself.
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, KolDO.class);
    TableInfoHelper.initTableInfo(assistant, KolPromotionRecordDO.class);
  }

  private final KolMapper kols = mock(KolMapper.class);
  private final KolPromotionRecordMapper records = mock(KolPromotionRecordMapper.class);
  private final KolCleanupService service =
      new KolCleanupService(
          kols,
          records,
          mock(CurrentUserContext.class),
          mock(ExternalAuditApi.class),
          mock(AuditSanitizer.class),
          mock(ApplicationEventPublisher.class));

  @Test
  @SuppressWarnings("unchecked")
  void scopedPromotingQueryCoversOnlyRequestedIds() {
    when(kols.selectList(any())).thenReturn(List.of(kol(1L)));
    when(records.selectList(any())).thenReturn(List.of());

    List<PromotingKolDTO> out = service.listPromotingWithLastPromotingAt(List.of(1L, 2L, 3L));

    assertThat(out).extracting(PromotingKolDTO::id).containsExactly(1L);
    ArgumentCaptor<LambdaQueryWrapper<KolDO>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(kols).selectList(captor.capture());
    // Building the SQL segment materializes the IN parameters into the bound-value map.
    assertThat(captor.getValue().getSqlSegment()).contains("IN");
    List<Long> boundIds =
        captor.getValue().getParamNameValuePairs().values().stream()
            .filter(Long.class::isInstance)
            .map(Long.class::cast)
            .toList();
    assertThat(boundIds).containsExactlyInAnyOrder(1L, 2L, 3L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void unscopedPromotingQueryAppliesNoIdFilter() {
    when(kols.selectList(any())).thenReturn(List.of());
    when(records.selectList(any())).thenReturn(List.of());

    assertThat(service.listPromotingWithLastPromotingAt()).isEmpty();

    ArgumentCaptor<LambdaQueryWrapper<KolDO>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(kols).selectList(captor.capture());
    assertThat(captor.getValue().getSqlSegment()).doesNotContain("IN");
  }

  private static KolDO kol(long id) {
    KolDO kol = new KolDO();
    kol.setId(id);
    kol.setKolCode("KOL-AAA001");
    kol.setName("A");
    kol.setAutoCleanupDays(10);
    return kol;
  }
}
