-- Exact lookup of the Weidian target item within a company.
CREATE INDEX CONCURRENTLY idx_crm_copy_job_item_tenant_target
  ON crm_copy_job_item (tenant_id, target_item_id)
  WHERE target_item_id IS NOT NULL;
