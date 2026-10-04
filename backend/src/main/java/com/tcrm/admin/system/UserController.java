package com.tcrm.admin.system;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.tcrm.admin.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin-api/system/user")
@SaCheckRole("SUPER_ADMIN")
public class UserController {
  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  public record CreateRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password,
      @NotBlank @Size(max = 64) String nickname,
      @Email @Size(max = 254) String email,
      @NotNull Role role,
      @NotNull DataScope dataScope) {}

  public record EditRequest(
      @NotBlank @Size(max = 64) String nickname,
      @Email @Size(max = 254) String email,
      @NotNull Role role,
      @NotNull DataScope dataScope) {}

  public record StatusRequest(@NotNull @Min(0) @Max(1) Integer status) {}

  public record ResetPasswordRequest(
      @NotBlank @Size(min = 10, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password) {}

  @GetMapping
  public R<PageResult<UserInfo>> list(
      @RequestParam(defaultValue = "1") @Min(1) long page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) Role role,
      @RequestParam(required = false) @Min(0) @Max(1) Integer status) {
    return R.ok(users.list(page, size, username, role, status));
  }

  @PostMapping
  @AuditLog(module = "User", action = "Create")
  public R<UserInfo> create(@Valid @RequestBody CreateRequest r) {
    return R.ok(users.create(r));
  }

  @PutMapping("/{id}")
  @AuditLog(module = "User", action = "Edit")
  public R<Void> edit(@PathVariable Long id, @Valid @RequestBody EditRequest r) {
    users.edit(id, r);
    return R.ok(null);
  }

  @PutMapping("/{id}/status")
  @AuditLog(module = "User", action = "Change status")
  public R<Void> status(@PathVariable Long id, @Valid @RequestBody StatusRequest r) {
    users.status(id, r.status());
    return R.ok(null);
  }

  @PutMapping("/{id}/password")
  @AuditLog(module = "User", action = "Reset password")
  public R<Void> password(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest r) {
    users.password(id, r.password());
    return R.ok(null);
  }
}
