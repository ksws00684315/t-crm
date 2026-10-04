package com.tcrm.admin.system;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdmin.class);
  private final SysUserMapper users;
  private final BCryptPasswordEncoder passwords;
  private final String username;
  private final String password;

  public BootstrapAdmin(
      SysUserMapper users,
      BCryptPasswordEncoder passwords,
      @Value("${crm.bootstrap.username}") String username,
      @Value("${crm.bootstrap.password}") String password) {
    this.users = users;
    this.passwords = passwords;
    this.username = username;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (users.selectCount(new QueryWrapper<SysUser>().eq("role", Role.SUPER_ADMIN)) > 0) return;
    if (username.isBlank() || password.isBlank()) {
      LOG.warn("Bootstrap administrator environment variables are missing; no account created");
      return;
    }
    if (!username.matches("[A-Za-z0-9_.-]{3,64}") || !password.matches("[\\x20-\\x7E]{10,72}"))
      throw new IllegalStateException(
          "Bootstrap credentials do not meet username/password validation requirements");
    SysUser user = new SysUser();
    user.username = username;
    user.passwordHash = passwords.encode(password);
    user.nickname = username;
    user.role = Role.SUPER_ADMIN;
    user.dataScope = DataScope.ALL;
    user.status = 1;
    user.deleted = false;
    users.insert(user);
    LOG.info("Bootstrap administrator created");
  }
}
