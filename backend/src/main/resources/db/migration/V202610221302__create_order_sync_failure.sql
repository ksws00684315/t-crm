-- Task 40 (row 43, 09.04): sync and import failure feed for the monitoring view.

CREATE TABLE crm_order_sync_failure (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  shop_id BIGINT,
  kind VARCHAR(16) NOT NULL,
  error VARCHAR(500) NOT NULL,
  task_id BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  create_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_order_sync_failure PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_sync_failure_id CHECK (id > 0),
  CONSTRAINT ck_crm_order_sync_failure_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_order_sync_failure_kind CHECK (kind IN ('ORDER_SYNC', 'REFUND_SYNC', 'ORDER_IMPORT', 'REFUND_IMPORT'))
);

CREATE INDEX idx_crm_order_sync_failure_tenant_time ON crm_order_sync_failure (tenant_id, create_time DESC);
CREATE INDEX idx_crm_order_sync_failure_shop_time ON crm_order_sync_failure (shop_id, kind, create_time);

COMMENT ON TABLE crm_order_sync_failure IS '订单同步与导入失败记录（任务 40）：同步监控的失败次数与失败任务列表';
COMMENT ON COLUMN crm_order_sync_failure.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order_sync_failure.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_order_sync_failure.shop_id IS '店铺 crm_shop.id；导入任务级失败（跨店铺）为空';
COMMENT ON COLUMN crm_order_sync_failure.kind IS '失败类型：ORDER_SYNC / REFUND_SYNC 接口同步，ORDER_IMPORT / REFUND_IMPORT 导入';
COMMENT ON COLUMN crm_order_sync_failure.error IS '安全英文摘要，不含令牌或个人信息';
COMMENT ON COLUMN crm_order_sync_failure.task_id IS '关联导入任务 crm_task.id；接口同步失败为空';
COMMENT ON COLUMN crm_order_sync_failure.create_time IS '失败时间（UTC）';
COMMENT ON COLUMN crm_order_sync_failure.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_failure.update_time IS '修改时间（UTC）；只追加，与创建时间相同';
COMMENT ON COLUMN crm_order_sync_failure.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_failure.is_deleted IS '逻辑删除';
