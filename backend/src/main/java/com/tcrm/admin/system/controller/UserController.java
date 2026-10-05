package com.tcrm.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.shared.AuditLog;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.R;
import com.tcrm.admin.system.service.CreateUserCommand;
import com.tcrm.admin.system.service.EditUserCommand;
import com.tcrm.admin.system.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Super administrators manage accounts; validation 400, missing 404, duplicate 409, business rules
 * 422.
 */
@RestController
@RequestMapping("/admin-api/system/users")
@SaCheckRole("SUPER_ADMIN")
public class UserController {
  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  @GetMapping
  public R<PageResult<UserVO>> list(@Valid @ModelAttribute UserQuery q) {
    return R.ok(
        users.page(q.page(), q.size(), q.username(), q.role(), q.status()).map(UserVO::from));
  }

  @PostMapping
  @AuditLog(module = "User", action = "Create")
  public R<UserVO> create(@Valid @RequestBody CreateUserRequest r) {
    return R.ok(
        UserVO.from(
            users.create(
                new CreateUserCommand(
                    r.username(),
                    r.password(),
                    r.nickname(),
                    r.email(),
                    r.role(),
                    r.dataScope()))));
  }

  @PutMapping("/{id}")
  @AuditLog(module = "User", action = "Edit")
  public R<Void> edit(@PathVariable @Min(1) Long id, @Valid @RequestBody EditUserRequest r) {
    users.update(id, new EditUserCommand(r.nickname(), r.email(), r.role(), r.dataScope()));
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @AuditLog(module = "User", action = "Change status")
  public R<Void> status(@PathVariable @Min(1) Long id, @Valid @RequestBody StatusRequest r) {
    users.updateStatus(id, r.status());
    return R.ok(null);
  }

  @PutMapping("/{id}/password")
  @AuditLog(module = "User", action = "Reset password")
  public R<Void> password(
      @PathVariable @Min(1) Long id, @Valid @RequestBody ResetPasswordRequest r) {
    users.updatePassword(id, r.password());
    return R.ok(null);
  }
}
