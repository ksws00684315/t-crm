package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.system.service.UserPermissionService;
import java.util.List;

public record UserPermissionVO(List<String> permissions, DataScope dataScope) {
  public static UserPermissionVO from(UserPermissionService.UserPermissionView view) {
    return new UserPermissionVO(view.permissions(), view.dataScope());
  }
}
