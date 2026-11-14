-- Task 145 (audit B-5): one failing order detail no longer fails the whole shop sync. The order is
-- skipped, recorded here and retried on every following sync of the shop until it succeeds.

CREATE TABLE crm_order_sync_retry (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  order_id VARCHAR(32) NOT NULL,
  fail_count INTEGER NOT NULL,
  last_error VARCHAR(500) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  create_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_order_sync_retry PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_sync_retry_id CHECK (id > 0),
  CONSTRAINT ck_crm_order_sync_retry_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_order_sync_retry_fail_count CHECK (fail_count > 0)
);

CREATE UNIQUE INDEX uk_crm_order_sync_retry_order ON crm_order_sync_retry (tenant_id, shop_id, order_id);

COMMENT ON TABLE crm_order_sync_retry IS '订单同步待重试订单（任务 145）：单个订单详情拉取或保存失败时跳过该订单、店铺同步继续，失败订单在之后每轮同步中重试，成功后删除本行';
COMMENT ON COLUMN crm_order_sync_retry.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order_sync_retry.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_order_sync_retry.shop_id IS '店铺 crm_shop.id';
COMMENT ON COLUMN crm_order_sync_retry.order_id IS '微店订单号（纯数字）';
COMMENT ON COLUMN crm_order_sync_retry.fail_count IS '连续失败次数（含首次）；达到 3 次时通知超级管理员一次';
COMMENT ON COLUMN crm_order_sync_retry.last_error IS '最近一次失败的安全英文摘要，不含令牌或个人信息';
COMMENT ON COLUMN crm_order_sync_retry.create_time IS '首次失败时间（UTC）';
COMMENT ON COLUMN crm_order_sync_retry.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_retry.update_time IS '最近一次失败时间（UTC）';
COMMENT ON COLUMN crm_order_sync_retry.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_retry.is_deleted IS '逻辑删除；重试成功后直接物理删除，本列恒为 FALSE';
