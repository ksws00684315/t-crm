package com.tcrm.admin.notification.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.tcrm.admin.notification.service.NotificationService;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in company account reads and marks only its own messages. These endpoints are not
 * audited. A platform administrator is rejected by the role annotation (403).
 */
@RestController
@RequestMapping("/admin-api/notification/notifications")
@SaCheckRole(
    value = {"SUPER_ADMIN", "STAFF"},
    mode = SaMode.OR)
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public R<PageResult<NotificationVO>> page(@Valid @ModelAttribute NotificationQuery query) {
    return R.ok(
        notifications.page(query.page(), query.size(), query.unread()).map(NotificationVO::from));
  }

  @GetMapping("/unread-count")
  public R<UnreadCountVO> unreadCount() {
    return R.ok(new UnreadCountVO(notifications.unreadCount()));
  }

  @PutMapping("/read-all")
  public R<Void> readAll() {
    notifications.markAllRead();
    return R.ok(null);
  }

  @PutMapping("/{id}/read")
  public R<Void> read(@PathVariable long id) {
    notifications.markRead(id);
    return R.ok(null);
  }
}
