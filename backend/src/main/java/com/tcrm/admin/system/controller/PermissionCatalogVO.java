package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Permission;
import com.tcrm.admin.system.service.UserPermissionService;
import java.util.List;

public record PermissionCatalogVO(List<Item> permissions, List<Template> templates) {
  public record Item(String code, Permission.Group group) {}

  public record Template(String code, List<String> permissions, DataScope dataScope) {}

  public static PermissionCatalogVO from(UserPermissionService.PermissionCatalog catalog) {
    return new PermissionCatalogVO(
        catalog.permissions().stream().map(item -> new Item(item.code(), item.group())).toList(),
        catalog.templates().stream()
            .map(
                template ->
                    new Template(template.code(), template.permissions(), template.dataScope()))
            .toList());
  }
}
