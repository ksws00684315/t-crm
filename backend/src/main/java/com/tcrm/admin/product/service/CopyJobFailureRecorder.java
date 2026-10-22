package com.tcrm.admin.product.service;

import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a task-level failure in its own transaction. {@code convert} still throws, and its
 * transaction rolls the attempt back; this row stays so the admin list can show the error.
 */
@Component
public class CopyJobFailureRecorder {
  private final CopyJobMapper jobs;

  public CopyJobFailureRecorder(CopyJobMapper jobs) {
    this.jobs = jobs;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void record(CopyJobDO job) {
    jobs.insert(job);
  }
}
