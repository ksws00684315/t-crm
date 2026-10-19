package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.KolCleanupItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi.DeleteResult;
import com.tcrm.admin.product.mapper.KolCleanupItemMapper;
import com.tcrm.admin.product.model.KolCleanupItemDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cleanup detail persistence and Weidian delete worker (task 29 / ADR-025). */
@Service
public class KolCleanupItemService implements KolCleanupItemApi {
  private final KolCleanupItemMapper items;
  private final KolItemLookupService links;
  private final WeidianItemCopyApi weidian;

  public KolCleanupItemService(
      KolCleanupItemMapper items, KolItemLookupService links, WeidianItemCopyApi weidian) {
    this.items = items;
    this.links = links;
    this.weidian = weidian;
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
  public void processAllPendingForKol(long kolId, Instant cleanupTime) {
    List<KolCleanupItemDO> pending =
        items.selectList(
            new LambdaQueryWrapper<KolCleanupItemDO>()
                .eq(KolCleanupItemDO::getKolId, kolId)
                .eq(KolCleanupItemDO::getCleanupTime, cleanupTime)
                .eq(KolCleanupItemDO::getStatus, "PENDING")
                .orderByAsc(KolCleanupItemDO::getId));
    for (KolCleanupItemDO row : pending) {
      applyDelete(row);
    }
  }

  @Override
  public java.util.Optional<KolCleanupItemDTO> getById(long id) {
    KolCleanupItemDO row = items.selectById(id);
    return row == null ? java.util.Optional.empty() : java.util.Optional.of(toDto(row));
  }

  @Override
  public void retryFailed(long cleanupItemId) {
    KolCleanupItemDO row = items.selectById(cleanupItemId);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (!"FAILED".equals(row.getStatus())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    applyDelete(row);
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
    return new PageResult<>(
        result.getRecords().stream().map(this::toDto).toList(), result.getTotal(), page, size);
  }

  private void applyDelete(KolCleanupItemDO row) {
    DeleteResult result = weidian.deleteItem(row.getShopId(), row.getWeidianItemId());
    Instant now = Instant.now();
    LambdaUpdateWrapper<KolCleanupItemDO> update =
        new LambdaUpdateWrapper<KolCleanupItemDO>()
            .eq(KolCleanupItemDO::getId, row.getId())
            .set(KolCleanupItemDO::getFinishTime, now)
            .set(KolCleanupItemDO::getUpdateTime, now);
    if (result.success()) {
      update.set(KolCleanupItemDO::getStatus, "SUCCESS").set(KolCleanupItemDO::getFailReason, null);
    } else {
      update
          .set(KolCleanupItemDO::getStatus, "FAILED")
          .set(KolCleanupItemDO::getFailReason, truncate(result.failReason(), 500));
    }
    items.update(null, update);
  }

  private KolCleanupItemDTO toDto(KolCleanupItemDO row) {
    return new KolCleanupItemDTO(
        row.getId(),
        row.getKolId(),
        row.getKolCode(),
        row.getBdUserId(),
        row.getKolItemId(),
        row.getShopId(),
        row.getWeidianItemId(),
        row.getStatus(),
        row.getTriggerType(),
        row.getReason(),
        row.getFailReason(),
        row.getCleanupTime(),
        row.getFinishTime());
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
