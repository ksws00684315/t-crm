-- Task 38: columns the product-task admin page reads. Existing rows stay valid;
-- V202610221101 backfills channel, finish time and source shop, then requires the channel.

ALTER TABLE crm_copy_job
  ADD COLUMN target_channel VARCHAR(16),
  ADD COLUMN error_message VARCHAR(500),
  ADD COLUMN finish_time TIMESTAMPTZ(3);

ALTER TABLE crm_copy_job
  ADD CONSTRAINT ck_crm_copy_job_channel CHECK (target_channel IN ('WEIDIAN'));

COMMENT ON COLUMN crm_copy_job.target_channel IS '目标渠道；目前仅 WEIDIAN。按复制令牌回填，令牌没有渠道时写 WEIDIAN';
COMMENT ON COLUMN crm_copy_job.error_message IS '任务级错误（中文，不含令牌或密钥），例如店铺未授权；空表示没有任务级错误，明细失败看明细';
COMMENT ON COLUMN crm_copy_job.finish_time IS '进入终态（completed / failed / partial_failed）的时间（UTC）；处理中为空';

ALTER TABLE crm_copy_job_item
  ADD COLUMN source_shop_id BIGINT;

ALTER TABLE crm_copy_job_item
  ADD CONSTRAINT ck_crm_copy_job_item_source_shop
  CHECK (source_shop_id IS NULL OR source_shop_id > 0);

COMMENT ON COLUMN crm_copy_job_item.source_shop_id IS '来源商品所属店铺 crm_shop.id；多家店铺都有该商品或查不到时为空';

-- Filtered list: company + channel + status, newest first. Unfiltered lists keep
-- idx_crm_copy_job_tenant_time (tenant_id, create_time DESC).
CREATE INDEX idx_crm_copy_job_tenant_channel_status
  ON crm_copy_job (tenant_id, target_channel, status, create_time DESC, id DESC);

-- Detail page: one job, creation order.
CREATE INDEX idx_crm_copy_job_item_job_id
  ON crm_copy_job_item (job_id, id);
