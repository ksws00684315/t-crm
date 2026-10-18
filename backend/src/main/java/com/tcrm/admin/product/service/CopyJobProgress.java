package com.tcrm.admin.product.service;

import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Recounts success/fail and closes a copy job when no pending items remain. */
@Component
public class CopyJobProgress {
  private final CopyJobMapper jobs;
  private final CopyJobItemMapper items;

  public CopyJobProgress(CopyJobMapper jobs, CopyJobItemMapper items) {
    this.jobs = jobs;
    this.items = items;
  }

  public void recountAndMaybeFinish(long jobId) {
    CopyJobDO job = jobs.selectById(jobId);
    if (job == null) {
      return;
    }
    int success = 0;
    int fail = 0;
    int pending = 0;
    for (CopyJobItemDO row : items.listByJob(jobId)) {
      if (CopyJobItemDO.STATUS_SUCCESS.equals(row.getStatus())) {
        success++;
      } else if (CopyJobItemDO.STATUS_FAILED.equals(row.getStatus())) {
        fail++;
      } else {
        pending++;
      }
    }
    String status;
    if (pending == 0) {
      if (fail == 0) {
        status = CopyJobDO.STATUS_COMPLETED;
      } else if (success == 0) {
        status = CopyJobDO.STATUS_FAILED;
      } else {
        status = CopyJobDO.STATUS_PARTIAL_FAILED;
      }
    } else {
      status = CopyJobDO.STATUS_PROCESSING;
    }
    jobs.updateProgress(jobId, success, fail, status, Instant.now());
  }
}
