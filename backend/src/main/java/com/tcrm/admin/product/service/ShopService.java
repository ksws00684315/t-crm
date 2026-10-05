package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Shop management (task 05 §4.1). At most 3 shops; only SUPER_ADMIN may write (controller). */
@Service
public class ShopService {
  private final ShopMapper shops;
  private final CurrentUserContext current;

  public ShopService(ShopMapper shops, CurrentUserContext current) {
    this.shops = shops;
    this.current = current;
  }

  public List<ShopDTO> list() {
    return shops
        .selectList(
            new LambdaQueryWrapper<ShopDO>()
                .orderByAsc(ShopDO::getId))
        .stream()
        .map(ShopService::toDto)
        .toList();
  }

  public ShopDTO create(CreateShopCommand c) {
    if (shops.countAlive() >= ProductRules.MAX_SHOPS) {
      throw new BusinessException(ErrorCode.SHOP_LIMIT_REACHED);
    }
    String weidianId = c.weidianShopId().strip();
    if (!ProductRules.isWeidianId(weidianId)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    AccountDTO user = current.user();
    ShopDO shop = new ShopDO();
    shop.setShopName(c.shopName().strip());
    shop.setWeidianShopId(weidianId);
    shop.setStatus(1);
    shop.setRemark(ProductRules.blankToNull(c.remark()));
    shop.setDeleted(false);
    try {
      shops.insert(shop);
    } catch (DuplicateKeyException e) {
      throw new BusinessException(ErrorCode.SHOP_WEIDIAN_ID_EXISTS);
    }
    ShopDO saved = shops.selectById(shop.getId());
    return toDto(saved == null ? shop : saved);
  }

  public void update(Long id, EditShopCommand c) {
    require(id);
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getShopName, c.shopName().strip())
            .set(ShopDO::getRemark, ProductRules.blankToNull(c.remark()))
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
  }

  public void updateStatus(Long id, int status) {
    require(id);
    Instant now = Instant.now();
    shops.update(
        null,
        new LambdaUpdateWrapper<ShopDO>()
            .eq(ShopDO::getId, id)
            .set(ShopDO::getStatus, status)
            .set(ShopDO::getUpdateBy, current.id())
            .set(ShopDO::getUpdateTime, now));
  }

  /** Alive and enabled shop, or SHOP_NOT_FOUND / SHOP_DISABLED. */
  public ShopDO requireEnabled(Long id) {
    ShopDO shop = require(id);
    if (shop.getStatus() == null || shop.getStatus() != 1) {
      throw new BusinessException(ErrorCode.SHOP_DISABLED);
    }
    return shop;
  }

  /** Alive shop by weidian shop id (enabled), or Chinese failure reason for import. */
  public ShopDO findEnabledByWeidianId(String weidianShopId) {
    return shops.selectOne(
        new LambdaQueryWrapper<ShopDO>()
            .eq(ShopDO::getWeidianShopId, weidianShopId)
            .eq(ShopDO::getStatus, 1)
            .last("LIMIT 1"));
  }

  public ShopDO require(Long id) {
    ShopDO shop = shops.selectById(id);
    if (shop == null) {
      throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
    }
    return shop;
  }

  public ShopDO getById(Long id) {
    return shops.selectById(id);
  }

  static ShopDTO toDto(ShopDO shop) {
    return new ShopDTO(
        shop.getId(),
        shop.getShopName(),
        shop.getWeidianShopId(),
        shop.getStatus(),
        shop.getRemark(),
        shop.getCreateTime(),
        shop.getUpdateTime());
  }
}
