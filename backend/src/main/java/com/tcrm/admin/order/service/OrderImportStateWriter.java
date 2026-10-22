package com.tcrm.admin.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.order.mapper.OrderSyncStateMapper;
import com.tcrm.admin.order.model.OrderSyncStateDO;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers each shop's latest import (data cutoff time) on {@code crm_order_sync_state} (task 40
 * §3.5, row 09.03). Called from import workers inside the owning company's scope.
 */
@Service
public class OrderImportStateWriter {
  private final OrderSyncStateMapper states;

  public OrderImportStateWriter(OrderSyncStateMapper states) {
    this.states = states;
  }

  /**
   * Marks the shop as last imported by this task. Runs in its own short transaction; an import row
   * already committed stays committed when this fails.
   *
   * @param shopId shop whose rows the file contained
   * @param kind ORDER or REFUND
   * @param taskId import task id
   * @param dataCutoffAt data cutoff time declared with the upload
   */
  @Transactional(rollbackFor = Exception.class)
  public void record(Long shopId, String kind, Long taskId, Instant dataCutoffAt) {
    var state =
        states.selectOne(
            new LambdaQueryWrapper<OrderSyncStateDO>()
                .eq(OrderSyncStateDO::getShopId, shopId)
                .last("FOR UPDATE"));
    if (state == null) {
      state = new OrderSyncStateDO();
      state.setShopId(shopId);
    }
    state.setLastImportKind(kind);
    state.setLastImportCutoffAt(dataCutoffAt);
    state.setLastImportTaskId(taskId);
    if (state.getId() == null) {
      states.insert(state);
    } else {
      states.updateById(state);
    }
  }
}
