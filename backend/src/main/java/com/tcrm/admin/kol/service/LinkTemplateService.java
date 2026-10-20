package com.tcrm.admin.kol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.api.LinkTemplateChangedEvent;
import com.tcrm.admin.kol.mapper.LinkTemplateMapper;
import com.tcrm.admin.kol.model.LinkTemplateDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TransactionalEvents;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent link template management (task 11 §4.2). Templates belong to the current company, are
 * disabled rather than deleted, and the code is immutable. A URL or status change publishes {@link
 * LinkTemplateChangedEvent} in the same transaction so every KOL using the template has its link
 * index refresh registered (ADR-013).
 */
@Service
public class LinkTemplateService {
  private final LinkTemplateMapper templates;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  public LinkTemplateService(
      LinkTemplateMapper templates,
      CurrentUserContext current,
      TransactionTemplate tx,
      ApplicationEventPublisher events) {
    this.templates = templates;
    this.current = current;
    this.tx = tx;
    this.events = events;
  }

  /** Every template of the current company, disabled ones included, ordered by sort, then id. */
  public List<LinkTemplateDTO> list() {
    return templates
        .selectList(
            new LambdaQueryWrapper<LinkTemplateDO>()
                .orderByAsc(LinkTemplateDO::getSortOrder, LinkTemplateDO::getId))
        .stream()
        .map(LinkTemplateService::toDto)
        .toList();
  }

  public LinkTemplateDTO create(CreateLinkTemplateCommand c) {
    AccountDTO user = current.user();
    LinkTemplateDO template = new LinkTemplateDO();
    template.setTemplateCode(c.templateCode());
    template.setTemplateName(c.templateName());
    template.setTemplateUrl(c.templateUrl());
    template.setChannelCode("WEIDIAN");
    template.setSortOrder(c.sortOrder());
    template.setStatus(1);
    template.setDeleted(false);
    tx.executeWithoutResult(
        status -> {
          try {
            templates.insert(template);
          } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.LINK_TEMPLATE_CODE_EXISTS);
          }
        });
    LinkTemplateDO saved = templates.selectById(template.getId());
    return toDto(saved == null ? template : saved);
  }

  /** Edits name, URL and sort order; the code cannot change. */
  public void update(Long id, EditLinkTemplateCommand c) {
    AccountDTO user = current.user();
    LinkTemplateDO existing = require(id);
    Instant now = Instant.now();
    tx.executeWithoutResult(
        status -> {
          requireUpdated(
              templates.update(
                  null,
                  new LambdaUpdateWrapper<LinkTemplateDO>()
                      .eq(LinkTemplateDO::getId, id)
                      .set(LinkTemplateDO::getTemplateName, c.templateName())
                      .set(LinkTemplateDO::getTemplateUrl, c.templateUrl())
                      .set(LinkTemplateDO::getSortOrder, c.sortOrder())
                      .set(LinkTemplateDO::getUpdateBy, user.id())
                      .set(LinkTemplateDO::getUpdateTime, now)));
          if (!Objects.equals(existing.getTemplateUrl(), c.templateUrl())) {
            TransactionalEvents.publish(events, new LinkTemplateChangedEvent(id));
          }
        });
  }

  /** Enables or disables the template; a disabled one makes its KOLs fall back to Weidian. */
  public void updateStatus(Long id, int status) {
    AccountDTO user = current.user();
    LinkTemplateDO existing = require(id);
    Instant now = Instant.now();
    tx.executeWithoutResult(
        s -> {
          requireUpdated(
              templates.update(
                  null,
                  new LambdaUpdateWrapper<LinkTemplateDO>()
                      .eq(LinkTemplateDO::getId, id)
                      .set(LinkTemplateDO::getStatus, status)
                      .set(LinkTemplateDO::getUpdateBy, user.id())
                      .set(LinkTemplateDO::getUpdateTime, now)));
          if (!Objects.equals(existing.getStatus(), status)) {
            TransactionalEvents.publish(events, new LinkTemplateChangedEvent(id));
          }
        });
  }

  /**
   * The enabled template with this ID of the current company, or 400 LINK_TEMPLATE_INVALID; the
   * tenant line handler hides other companies' rows, so they are invalid too.
   */
  public LinkTemplateDTO usableTemplate(Long id) {
    if (id == null) {
      return null;
    }
    LinkTemplateDO template = templates.selectById(id);
    if (template == null || !Integer.valueOf(1).equals(template.getStatus())) {
      throw new BusinessException(ErrorCode.LINK_TEMPLATE_INVALID);
    }
    return toDto(template);
  }

  /** Enabled template of the current company by code, or null; the import uses this. */
  public LinkTemplateDTO usableTemplateByCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    LinkTemplateDO template =
        templates.selectOne(
            new LambdaQueryWrapper<LinkTemplateDO>()
                .eq(LinkTemplateDO::getTemplateCode, code.strip())
                .last("LIMIT 1"));
    if (template == null || !Integer.valueOf(1).equals(template.getStatus())) {
      return null;
    }
    return toDto(template);
  }

  /** Template names keyed by id for display; unknown ids are skipped. */
  public Map<Long, String> namesByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, String> names = new HashMap<>();
    for (LinkTemplateDO template : templates.selectBatchIds(ids)) {
      names.put(template.getId(), template.getTemplateName());
    }
    return names;
  }

  private static void requireUpdated(int updated) {
    if (updated == 0) {
      throw new BusinessException(ErrorCode.LINK_TEMPLATE_NOT_FOUND);
    }
  }

  private LinkTemplateDO require(Long id) {
    LinkTemplateDO template = templates.selectById(id);
    if (template == null) {
      throw new BusinessException(ErrorCode.LINK_TEMPLATE_NOT_FOUND);
    }
    return template;
  }

  private static LinkTemplateDTO toDto(LinkTemplateDO template) {
    return new LinkTemplateDTO(
        template.getId(),
        template.getTemplateCode(),
        template.getTemplateName(),
        template.getTemplateUrl(),
        template.getChannelCode(),
        template.getSortOrder(),
        template.getStatus(),
        template.getCreateTime());
  }
}
