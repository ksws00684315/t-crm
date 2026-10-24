package com.tcrm.admin.system.service;

import com.tcrm.admin.system.api.AccountNameApi;
import com.tcrm.admin.system.api.AccountNameDTO;
import com.tcrm.admin.system.mapper.SysUserMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** Display names that stay visible after an account is disabled or deleted. */
@Service
public class AccountNameService implements AccountNameApi {
  private final SysUserMapper users;

  public AccountNameService(SysUserMapper users) {
    this.users = users;
  }

  @Override
  public List<AccountNameDTO> listByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return users.listNamesByIds(ids);
  }
}
