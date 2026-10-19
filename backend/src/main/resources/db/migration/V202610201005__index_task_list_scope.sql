-- Task 33: support company task lists and submitter-scoped staff task lists.
CREATE INDEX idx_crm_task_tenant_create_by_time
  ON crm_task (tenant_id, create_by, create_time DESC, id DESC);
COMMENT ON INDEX idx_crm_task_tenant_create_by_time IS
  '任务列表按公司、提交人及创建时间排序查询';
