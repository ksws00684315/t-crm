package com.tcrm.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.AccountStore;
import com.tcrm.admin.system.mapper.SysUserMapper;
import com.tcrm.admin.system.model.SysUserDO;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AccountStoreService implements AccountStore {
  private final SysUserMapper users;

  public AccountStoreService(SysUserMapper users) {
    this.users = users;
  }

  @Override
  public AccountDTO getByUsername(String username) {
    return snapshot(
        users.selectOne(
            new LambdaQueryWrapper<SysUserDO>().apply("lower(username) = lower({0})", username)));
  }

  @Override
  public AccountDTO getById(Long id) {
    return snapshot(users.selectById(id));
  }

  @Override
  public void updateLastLoginTime(Long id, Instant time) {
    SysUserDO user = new SysUserDO();
    user.setId(id);
    user.setLastLoginTime(time);
    users.updateById(user);
  }

  @Override
  public void updatePassword(Long id, String hash) {
    SysUserDO user = new SysUserDO();
    user.setId(id);
    user.setPasswordHash(hash);
    users.updateById(user);
  }

  private AccountDTO snapshot(SysUserDO user) {
    return user == null
        ? null
        : new AccountDTO(
            user.getId(),
            user.getUsername(),
            user.getPasswordHash(),
            user.getNickname(),
            user.getEmail(),
            user.getRole(),
            user.getDataScope(),
            user.getStatus(),
            user.getLastLoginTime(),
            user.getCreateTime());
  }
}
