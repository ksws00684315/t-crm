package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.KolCleanupItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cleanup detail persistence and enqueue to the Weidian-delete worker (task 29 / ADR-025). */
@Service
public class KolCleanupItemService implements KolCleanupItemApi {
  private final KolCleanupItemMapper items;
  private final KolItemLookupService links;
  private final ShopMapper shops;
  private final KolCleanupWorker worker;

  public KolCleanupItemService(
      KolCleanupItemMapper items,
      KolItemLookupService links,
      ShopMapper shops,
      KolCleanupWorker worker) {
    this.items = items;
    this.links = links;
    this.shops = shops;
    this.worker = worker;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Long> createPendingItems(
      long kolId,
      String kolCode,
      Long bdUserId,
      String triggerType,
      String reason,
      Instant cleanupTime,
      Long actorId) {
    List<KolItemLinkDTO> mappings = links.listCurrentLinksByKol(kolId);
    List<Long> ids = new ArrayList<>(mappings.size());
    for (KolItemLinkDTO link : mappings) {
      KolCleanupItemDO row = new KolCleanupItemDO();
      row.setKolId(kolId);
      row.setKolCode(kolCode);
      row.setBdUserId(bdUserId);
      row.setKolItemId(link.kolItemId());
      row.setShopId(link.shopId());
      row.setWeidianItemId(link.weidianItemId());
      row.setStatus("PENDING");
      row.setTriggerType(triggerType);
      row.setReason(reason);
      row.setCleanupTime(cleanupTime);
      row.setCreateBy(actorId);
      row.setUpdateBy(actorId);
      row.setDeleted(false);
      items.insert(row);
      ids.add(row.getId());
    }
    return ids;
  }

  @Override
  public void enqueueDeletesForKol(long kolId) {
    worker.submit(kolId);
  }

  @Override
  public Optional<KolCleanupItemDTO> getById(long id) {
    KolCleanupItemDO row = items.selectById(id);
    if (row == null) {
      return Optional.empty();
    }
    Map<Long, String> names = shopNames(Set.of(row.getShopId()));
    return Optional.of(toDto(row, names));
  }

  @Override
  public void retryFailed(long cleanupItemId) {
    Instant now = Instant.now();
    int claimed =
        items.update(
            null,
            new LambdaUpdateWrapper<KolCleanupItemDO>()
                .eq(KolCleanupItemDO::getId, cleanupItemId)
                .eq(KolCleanupItemDO::getStatus, "FAILED")
                .set(KolCleanupItemDO::getStatus, "PENDING")
                .set(KolCleanupItemDO::getFailReason, null)
                .set(KolCleanupItemDO::getFinishTime, null)
                .set(KolCleanupItemDO::getUpdateTime, now));
    if (claimed != 1) {
      KolCleanupItemDO row = items.selectById(cleanupItemId);
      if (row == null) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    KolCleanupItemDO row = items.selectById(cleanupItemId);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    worker.submit(row.getKolId());
  }

  @Override
  public PageResult<KolCleanupItemDTO> page(
      String kolCode, String status, Long bdUserIdScope, int page, int size) {
    var query =
        new LambdaQueryWrapper<KolCleanupItemDO>()
            .orderByDesc(KolCleanupItemDO::getCleanupTime, KolCleanupItemDO::getId);
    if (kolCode != null && !kolCode.isBlank()) {
      query.eq(KolCleanupItemDO::getKolCode, kolCode.strip());
    }
    if (status != null && !status.isBlank()) {
      query.eq(KolCleanupItemDO::getStatus, status.strip());
    }
    if (bdUserIdScope != null) {
      query.eq(KolCleanupItemDO::getBdUserId, bdUserIdScope);
    }
    var result = items.selectPage(new Page<>(page, size), query);
    Set<Long> shopIds = new HashSet<>();
    for (KolCleanupItemDO row : result.getRecords()) {
      if (row.getShopId() != null) {
        shopIds.add(row.getShopId());
      }
    }
    Map<Long, String> names = shopNames(shopIds);
    return new PageResult<>(
        result.getRecords().stream().map(row -> toDto(row, names)).toList(),
        result.getTotal(),
        page,
        size);
  }

  private Map<Long, String> shopNames(Set<Long> shopIds) {
    Map<Long, String> names = new HashMap<>();
    if (shopIds == null || shopIds.isEmpty()) {
      return names;
    }
    for (ShopDO shop :
        shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, shopIds))) {
      names.put(shop.getId(), shop.getShopName());
    }
    return names;
  }

  private KolCleanupItemDTO toDto(KolCleanupItemDO row, Map<Long, String> shopNames) {
    return new KolCleanupItemDTO(
        row.getId(),
        row.getKolId(),
        row.getKolCode(),
        row.getBdUserId(),
        row.getKolItemId(),
        row.getShopId(),
        row.getShopId() == null ? null : shopNames.get(row.getShopId()),
        row.getWeidianItemId(),
        row.getStatus(),
        row.getTriggerType(),
        row.getReason(),
        row.getFailReason(),
        row.getCleanupTime(),
        row.getFinishTime());
  }
}
