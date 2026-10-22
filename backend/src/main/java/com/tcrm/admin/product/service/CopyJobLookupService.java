package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.product.api.CopyJobLookupApi;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.model.CopyJobItemDO;
import org.springframework.stereotype.Service;

/** Copy-job lookup for other modules (task 40: order chain). */
@Service
public class CopyJobLookupService implements CopyJobLookupApi {
  private final CopyJobItemMapper items;
  private final CopyJobMapper jobs;

  public CopyJobLookupService(CopyJobItemMapper items, CopyJobMapper jobs) {
    this.items = items;
    this.jobs = jobs;
  }

  @Override
  public CopyJobRefDTO findByTargetItemId(String targetItemId) {
    if (targetItemId == null || targetItemId.isBlank()) {
      return null;
    }
    var copied =
        items.selectOne(
            new LambdaQueryWrapper<CopyJobItemDO>()
                .eq(CopyJobItemDO::getTargetItemId, targetItemId)
                .isNotNull(CopyJobItemDO::getJobId)
                .orderByDesc(CopyJobItemDO::getId)
                .last("LIMIT 1"));
    if (copied == null) {
      return null;
    }
    var job = jobs.selectById(copied.getJobId());
    return job == null
        ? null
        : new CopyJobRefDTO(job.getJobCode(), job.getCreateTime(), job.getStatus());
  }
}
