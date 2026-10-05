package com.tcrm.admin.system.controller;

import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UserQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    @Size(max = 64) String username,
    Role role,
    @Min(0) @Max(1) Integer status) {
  public UserQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }
}
