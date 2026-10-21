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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.LinkTemplateChangedEvent;
import com.tcrm.admin.kol.mapper.LinkTemplateMapper;
import com.tcrm.admin.kol.model.LinkTemplateDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

class LinkTemplateServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), LinkTemplateDO.class);
  }

  private final LinkTemplateMapper templates = mock(LinkTemplateMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final LinkTemplateService service =
      new LinkTemplateService(templates, current, TestTransactions.template(), events);

  @BeforeEach
  void actAsAdmin() {
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L,
                "u",
                null,
                "U",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                null,
                null,
                true));
  }

  private static LinkTemplateDO template() {
    LinkTemplateDO template = new LinkTemplateDO();
    template.setId(77L);
    template.setTemplateCode("kakobuy");
    template.setTemplateName("kakobuy");
    template.setTemplateUrl("https://item.kakobuy.com/i/{PRODUCT_ID}");
    template.setChannelCode("WEIDIAN");
    template.setSortOrder(0);
    template.setStatus(1);
    template.setDeleted(false);
    template.setCreateTime(Instant.parse("2026-10-17T00:00:00Z"));
    template.setUpdateTime(Instant.parse("2026-10-17T00:00:00Z"));
    return template;
  }

  @Test
  void namesByIdsUsesOriginalCodeAsCustomerFacingPlatformName() {
    LinkTemplateDO existing = template();
    existing.setTemplateName("KakoBuy");
    when(templates.selectBatchIds(List.of(77L))).thenReturn(List.of(existing));

    assertThat(service.namesByIds(List.of(77L))).containsEntry(77L, "kakobuy");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldListAllTemplates_orderedBySortThenId() {
    LinkTemplateDO first = template();
    LinkTemplateDO second = template();
    second.setId(78L);
    LinkTemplateDO third = template();
    third.setId(79L);
    when(templates.selectList(any())).thenReturn(List.of(first, second, third));

    assertThat(service.list()).extracting(LinkTemplateDTO::id).containsExactly(77L, 78L, 79L);

    ArgumentCaptor<Wrapper<LinkTemplateDO>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(templates).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("ORDER BY sort_order ASC,id ASC");
  }

  @Test
  void shouldCreateEnabledWeidianTemplate_fromCommand() {
    when(templates.selectById(any())).thenReturn(template());

    LinkTemplateDTO created =
        service.create(
            new CreateLinkTemplateCommand(
                "kakobuy", "kakobuy", "https://item.kakobuy.com/i/{PRODUCT_ID}", 3));

    var saved = ArgumentCaptor.forClass(LinkTemplateDO.class);
    verify(templates).insert(saved.capture());
    assertThat(saved.getValue().getChannelCode()).isEqualTo("WEIDIAN");
    assertThat(saved.getValue().getStatus()).isEqualTo(1);
    assertThat(saved.getValue().getDeleted()).isFalse();
    assertThat(created.templateCode()).isEqualTo("kakobuy");
    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldReject409_whenCodeIsTakenInCompany() {
    when(templates.insert(any(LinkTemplateDO.class)))
        .thenThrow(new DuplicateKeyException("uk_crm_link_template_code"));
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateLinkTemplateCommand(
                        "kakobuy", "kakobuy", "https://item.kakobuy.com/i/{PRODUCT_ID}", 0)))
        .hasMessage(ErrorCode.LINK_TEMPLATE_CODE_EXISTS.message());
  }

  @Test
  void shouldReturn404_whenEditingOrTogglingMissingTemplate() {
    when(templates.selectById(77L)).thenReturn(null);
    assertThatThrownBy(
            () ->
                service.update(
                    77L, new EditLinkTemplateCommand("kakobuy", "https://x/{PRODUCT_ID}", 0)))
        .hasMessage(ErrorCode.LINK_TEMPLATE_NOT_FOUND.message());
    assertThatThrownBy(() -> service.updateStatus(77L, 0))
        .hasMessage(ErrorCode.LINK_TEMPLATE_NOT_FOUND.message());
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldUpdateNameUrlAndSort_withoutTouchingCode() {
    when(templates.selectById(77L)).thenReturn(template());
    when(templates.update(isNull(), any())).thenReturn(1);

    service.update(77L, new EditLinkTemplateCommand("kakobuy 2", "https://y/{PRODUCT_ID}", 7));

    var wrapper = ArgumentCaptor.forClass(Wrapper.class);
    verify(templates).update(isNull(), wrapper.capture());
    String sqlSet =
        ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LinkTemplateDO>)
                wrapper.getValue())
            .getSqlSet();
    assertThat(sqlSet).contains("template_name=", "template_url=", "sort_order=");
    verify(events).publishEvent(new LinkTemplateChangedEvent(77L));
  }

  @Test
  void shouldNotPublishEvent_whenOnlyNameOrSortChange() {
    LinkTemplateDO existing = template();
    existing.setTemplateName("old");
    existing.setSortOrder(9);
    when(templates.selectById(77L)).thenReturn(existing);
    when(templates.update(isNull(), any())).thenReturn(1);

    service.update(77L, new EditLinkTemplateCommand("new", existing.getTemplateUrl(), 0));

    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldPublishEvent_whenStatusChanges() {
    when(templates.selectById(77L)).thenReturn(template());
    when(templates.update(isNull(), any())).thenReturn(1);

    service.updateStatus(77L, 0);

    verify(events).publishEvent(new LinkTemplateChangedEvent(77L));
  }

  @Test
  void shouldNotPublishEvent_whenStatusIsAlreadyTheSame() {
    when(templates.selectById(77L)).thenReturn(template());
    when(templates.update(isNull(), any())).thenReturn(1);

    service.updateStatus(77L, 1);

    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldRequireEnabledCompanyTemplate_forKolUse() {
    when(templates.selectById(77L)).thenReturn(template());
    assertThat(service.usableTemplate(77L).id()).isEqualTo(77L);
    assertThat(service.usableTemplate(null)).isNull();

    LinkTemplateDO disabled = template();
    disabled.setStatus(0);
    when(templates.selectById(78L)).thenReturn(disabled);
    when(templates.selectById(79L)).thenReturn(null);
    assertThatThrownBy(() -> service.usableTemplate(78L))
        .hasMessage(ErrorCode.LINK_TEMPLATE_INVALID.message());
    assertThatThrownBy(() -> service.usableTemplate(79L))
        .hasMessage(ErrorCode.LINK_TEMPLATE_INVALID.message());
  }

  @Test
  void shouldLookUpEnabledTemplateByCode_forImport() {
    when(templates.selectOne(any())).thenReturn(template());
    assertThat(service.usableTemplateByCode("kakobuy").id()).isEqualTo(77L);
    assertThat(service.usableTemplateByCode(" ")).isNull();
    assertThat(service.usableTemplateByCode(null)).isNull();

    LinkTemplateDO disabled = template();
    disabled.setStatus(0);
    when(templates.selectOne(any())).thenReturn(disabled);
    assertThat(service.usableTemplateByCode("kakobuy")).isNull();
    when(templates.selectOne(any())).thenReturn(null);
    assertThat(service.usableTemplateByCode("ghost")).isNull();
  }

  @Test
  void shouldValidateUrlShape() {
    assertThat(LinkTemplateRules.isUrl("https://item.kakobuy.com/{PRODUCT_ID}")).isTrue();
    assertThat(LinkTemplateRules.isUrl("https://x/?a={INVITE_CODE}&b={PRODUCT_ID}")).isTrue();
    assertThat(LinkTemplateRules.isUrl("http://item.kakobuy.com/{PRODUCT_ID}")).isFalse();
    assertThat(LinkTemplateRules.isUrl("https://item.kakobuy.com/")).isFalse();
    assertThat(LinkTemplateRules.needsInviteCode("https://x/{PRODUCT_ID}")).isFalse();
    assertThat(LinkTemplateRules.needsInviteCode("https://x/{INVITE_CODE}/{PRODUCT_ID}")).isTrue();
    assertThat(LinkTemplateRules.isCode("kakobuy")).isTrue();
    assertThat(LinkTemplateRules.isCode("Kakobuy")).isFalse();
    assertThat(LinkTemplateRules.isCode("k")).isFalse();
  }
}
