package com.tcrm.admin.product.api;

import java.time.Instant;

/** Copy-job lookup for other modules (task 40: order chain links copied items to their job). */
public interface CopyJobLookupApi {
  /**
   * Returns the job that produced this exclusive item, or null when no copy-job item wrote this
   * target item ID. When several jobs claim the same target (not expected), the newest job wins.
   * Runs in the caller's current tenant scope.
   *
   * @param targetItemId Weidian item id created by the copy ({@code
   *     crm_copy_job_item.target_item_id})
   */
  CopyJobRefDTO findByTargetItemId(String targetItemId);

  /**
   * @param jobCode external job number shown in the chain (128-bit random, URL safe)
   * @param createTime when the job was created
   * @param status processing / completed / failed / partial_failed
   */
  record CopyJobRefDTO(String jobCode, Instant createTime, String status) {}
}
