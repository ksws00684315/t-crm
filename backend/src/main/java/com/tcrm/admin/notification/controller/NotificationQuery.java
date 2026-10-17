package com.tcrm.admin.notification.controller;

import com.tcrm.admin.shared.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record NotificationQuery(
    @Min(1) @Max(PageResult.MAX_PAGE) Integer page,
    @Min(1) @Max(100) Integer size,
    Boolean unread) {
  public NotificationQuery {
    page = page == null ? 1 : page;
    size = size == null ? 20 : size;
  }
}
