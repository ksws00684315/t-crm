package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.kol.mapper.LinkTemplateMapper;
import com.tcrm.admin.kol.model.KolDO;
import com.tcrm.admin.kol.model.LinkTemplateDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class KolLookupServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolDO.class);
  }

  private static LinkTemplateDO template(long id, int status) {
    LinkTemplateDO template = new LinkTemplateDO();
    template.setId(id);
    template.setTemplateCode("kakobuy");
    template.setTemplateName("kakobuy");
    template.setTemplateUrl("https://item.kakobuy.com/i/{PRODUCT_ID}?aff={INVITE_CODE}");
    template.setChannelCode("WEIDIAN");
    template.setSortOrder(0);
    template.setStatus(status);
    return template;
  }

  @Test
  void shouldReturnEnabledFlag_fromStatus() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    KolDO enabled = new KolDO();
    enabled.setId(1L);
    enabled.setKolCode("KOL0001");
    enabled.setName("A");
    enabled.setStatus(1);
    when(kols.selectOne(any())).thenReturn(enabled);
    KolRefDTO dto = new KolLookupService(kols, templates).getByCode("kol0001");
    assertThat(dto.enabled()).isTrue();
    assertThat(dto.kolCode()).isEqualTo("KOL0001");
  }

  @Test
  void shouldKeepDeletedKols_whenListingForDisplay() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    KolDO deleted = new KolDO();
    deleted.setId(9L);
    deleted.setKolCode("KOL0009");
    deleted.setName("Gone");
    deleted.setStatus(0);
    deleted.setBdUserId(4L);
    when(kols.listByIdsIncludingDeleted(List.of(9L))).thenReturn(List.of(deleted));

    assertThat(new KolLookupService(kols, templates).listByIdsIncludingDeleted(List.of(9L)))
        .containsExactly(new KolRefDTO(9L, "KOL0009", "Gone", false, 4L));
    verify(templates, never()).selectBatchIds(any());
    assertThat(new KolLookupService(kols, templates).listByIdsIncludingDeleted(List.of()))
        .isEmpty();
    verify(kols, never()).listByIdsIncludingDeleted(List.of());
  }

  @Test
  void shouldSkipUnknownIds_whenListing() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    when(kols.selectList(any())).thenReturn(List.of());
    assertThat(new KolLookupService(kols, templates).listByIds(List.of(1L, 2L))).isEmpty();
  }

  @Test
  void shouldExposeBdUser_whenListingAllKols() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    KolDO kol = new KolDO();
    kol.setId(1L);
    kol.setKolCode("KOL0001");
    kol.setName("A");
    kol.setStatus(0);
    kol.setBdUserId(5L);
    when(kols.selectList(any())).thenReturn(List.of(kol));
    List<KolRefDTO> all = new KolLookupService(kols, templates).listAll();
    assertThat(all).containsExactly(new KolRefDTO(1L, "KOL0001", "A", false, 5L));
  }

  @Test
  void shouldResolveLinkTargetInputs_whenListingAllKols() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    KolDO withTemplate = new KolDO();
    withTemplate.setId(1L);
    withTemplate.setKolCode("KOL0001");
    withTemplate.setName("A");
    withTemplate.setStatus(1);
    withTemplate.setInviteCode("aff-01");
    withTemplate.setLinkTemplateId(77L);
    KolDO withoutTemplate = new KolDO();
    withoutTemplate.setId(2L);
    withoutTemplate.setKolCode("KOL0002");
    withoutTemplate.setName("B");
    withoutTemplate.setStatus(1);
    when(kols.selectList(any())).thenReturn(List.of(withTemplate, withoutTemplate));
    when(templates.selectBatchIds(any(Collection.class))).thenReturn(List.of(template(77L, 1)));

    List<KolRefDTO> all = new KolLookupService(kols, templates).listAll();

    assertThat(all.get(0).inviteCode()).isEqualTo("aff-01");
    assertThat(all.get(0).linkTemplateUrl()).contains("{PRODUCT_ID}");
    assertThat(all.get(0).linkTemplateEnabled()).isTrue();
    assertThat(all.get(1).linkTemplateUrl()).isNull();
    assertThat(all.get(1).linkTemplateEnabled()).isFalse();
  }

  @Test
  void shouldPageKolIds_byTemplate() {
    KolMapper kols = mock(KolMapper.class);
    LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
    KolDO first = new KolDO();
    first.setId(1L);
    KolDO second = new KolDO();
    second.setId(2L);
    when(kols.selectList(any())).thenReturn(List.of(first, second));

    List<Long> ids = new KolLookupService(kols, templates).pageKolIdsWithTemplate(77L, null, 200);

    assertThat(ids).containsExactly(1L, 2L);
  }
}
