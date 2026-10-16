package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.integration.api.WeidianAuthorizationApi;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.integration.model.WeidianShopAuthDO;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WeidianAuthorizationLookup implements WeidianAuthorizationApi {
  private final WeidianShopAuthMapper auths;

  public WeidianAuthorizationLookup(WeidianShopAuthMapper auths) {
    this.auths = auths;
  }

  @Override
  public List<Long> authorizedShopIds() {
    return auths
        .selectList(
            new LambdaQueryWrapper<WeidianShopAuthDO>()
                .select(WeidianShopAuthDO::getShopId)
                .eq(WeidianShopAuthDO::getStatus, "AUTHORIZED"))
        .stream()
        .map(WeidianShopAuthDO::getShopId)
        .toList();
  }
}
