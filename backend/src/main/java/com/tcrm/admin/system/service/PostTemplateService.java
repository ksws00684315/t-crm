package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.system.mapper.SysPostTemplateMapper;
import com.tcrm.admin.system.model.SysPostTemplateDO;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PostTemplateService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final SysPostTemplateMapper templates;
  private final CurrentUserContext current;
  private final UserPermissionService permissions;

  public PostTemplateService(
      SysPostTemplateMapper templates,
      CurrentUserContext current,
      UserPermissionService permissions) {
    this.templates = templates;
    this.current = current;
    this.permissions = permissions;
  }

  public List<PostTemplateView> list() {
    long tenantId = current.user().tenantId();
    return List.of(PostTemplate.values()).stream()
        .map(template -> view(template, tenantId))
        .toList();
  }

  public PostTemplateView getDefault(PostTemplate template, long tenantId) {
    SysPostTemplateDO row = find(template, tenantId);
    if (row == null) {
      return defaultView(template);
    }
    return new PostTemplateView(
        template,
        row.getDataScope(),
        permissions.canonical(readCodes(row.getPermissions())),
        false);
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(PostTemplate template, DataScope dataScope, List<String> codes) {
    if (template == null || dataScope == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    List<String> normalized = permissions.canonical(codes);
    long tenantId = current.user().tenantId();
    SysPostTemplateDO row = find(template, tenantId);
    Instant now = Instant.now();
    if (row == null) {
      row = new SysPostTemplateDO();
      row.setTenantId(tenantId);
      row.setPostTemplate(template.name());
      row.setDataScope(dataScope);
      row.setPermissions(writeCodes(normalized));
      row.setUpdateBy(current.user().id());
      row.setUpdateTime(now);
      templates.insert(row);
    } else {
      row.setDataScope(dataScope);
      row.setPermissions(writeCodes(normalized));
      row.setUpdateBy(current.user().id());
      row.setUpdateTime(now);
      templates.updateById(row);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void reset(PostTemplate template) {
    if (template == null) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    templates.delete(
        new LambdaQueryWrapper<SysPostTemplateDO>()
            .eq(SysPostTemplateDO::getTenantId, current.user().tenantId())
            .eq(SysPostTemplateDO::getPostTemplate, template.name()));
  }

  private PostTemplateView view(PostTemplate template, long tenantId) {
    SysPostTemplateDO row = find(template, tenantId);
    return row == null
        ? defaultView(template)
        : new PostTemplateView(
            template,
            row.getDataScope(),
            permissions.canonical(readCodes(row.getPermissions())),
            false);
  }

  private SysPostTemplateDO find(PostTemplate template, long tenantId) {
    return templates.selectOne(
        new LambdaQueryWrapper<SysPostTemplateDO>()
            .eq(SysPostTemplateDO::getTenantId, tenantId)
            .eq(SysPostTemplateDO::getPostTemplate, template.name()));
  }

  private static PostTemplateView defaultView(PostTemplate template) {
    return new PostTemplateView(template, template.dataScope(), template.permissionCodes(), true);
  }

  private static List<String> readCodes(String json) {
    return json == null
        ? List.of()
        : JSON.readValue(
            json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private static String writeCodes(List<String> codes) {
    return JSON.writeValueAsString(codes);
  }

  public record PostTemplateView(
      PostTemplate postTemplate,
      DataScope dataScope,
      List<String> permissions,
      boolean isDefault) {}
}
