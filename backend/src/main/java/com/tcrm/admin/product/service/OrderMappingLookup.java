package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.api.OrderMappingApi;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class OrderMappingLookup implements OrderMappingApi {
  private final KolItemMapper items;

  public OrderMappingLookup(KolItemMapper items) {
    this.items = items;
  }

  @Override
  public KolItemRefDTO findAt(Long shopId, String itemId, Instant paidAt) {
    var found =
        items.selectList(
            new LambdaQueryWrapper<KolItemDO>()
                .eq(KolItemDO::getShopId, shopId)
                .eq(KolItemDO::getWeidianItemId, itemId)
                .le(KolItemDO::getValidFrom, paidAt)
                .and(q -> q.isNull(KolItemDO::getValidTo).or().gt(KolItemDO::getValidTo, paidAt))
                .last("LIMIT 2"));
    if (found.size() > 1) {
      throw new BusinessException(ErrorCode.COMMON_CONFLICT);
    }
    if (found.isEmpty()) {
      return null;
    }
    var row = found.getFirst();
    return new KolItemRefDTO(row.getId(), row.getKolId(), row.getProductId(), row.getTenantId());
  }
}
