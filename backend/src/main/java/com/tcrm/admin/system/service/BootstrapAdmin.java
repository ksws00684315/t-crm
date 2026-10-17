package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.AccountType;
import com.tcrm.admin.system.model.SysUserDO;
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
    TenantScope.runSystem(
        "bootstrap platform administrator",
        () -> {
          if (users.selectCount(
                  new LambdaQueryWrapper<SysUserDO>()
                      .eq(SysUserDO::getRole, AccountType.PLATFORM_ADMIN)
                      .eq(SysUserDO::getStatus, 1))
              > 0) {
            return;
          }
          if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                "No enabled platform administrator exists; set TCRM_BOOTSTRAP_ADMIN_USERNAME and"
                    + " TCRM_BOOTSTRAP_ADMIN_PASSWORD before starting the application");
          }
          if (!username.matches("[A-Za-z0-9_.-]{3,64}")
              || !password.matches("[\\x20-\\x7E]{10,72}")) {
            throw new IllegalStateException(
                "Bootstrap credentials do not meet username/password validation requirements");
          }
          if (users.countByUsername(username) > 0) {
            throw new IllegalStateException("该用户名已被占用，请为平台管理员设置另一个 TCRM_BOOTSTRAP_ADMIN_USERNAME");
          }
          SysUserDO user = new SysUserDO();
          user.setUsername(username);
          user.setPasswordHash(passwords.encode(password));
          user.setNickname(username);
          user.setRole(AccountType.PLATFORM_ADMIN);
          user.setStatus(1);
          user.setDeleted(false);
          users.insert(user);
          LOG.info("Bootstrap platform administrator created");
        });
  }
}
