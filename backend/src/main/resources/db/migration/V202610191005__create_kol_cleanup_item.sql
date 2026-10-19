-- Task 29 / ADR-025: cleanup detail rows for Weidian item deletion (product module table).

CREATE TABLE crm_kol_cleanup_item (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  kol_code VARCHAR(32) NOT NULL,
  bd_user_id BIGINT,
  kol_item_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_item_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  trigger_type VARCHAR(16) NOT NULL,
  reason VARCHAR(200),
  fail_reason VARCHAR(500),
  cleanup_time TIMESTAMPTZ(3) NOT NULL,
  finish_time TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_kol_cleanup_item PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_cleanup_item_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_cleanup_item_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_cleanup_item_kol CHECK (kol_id > 0),
  CONSTRAINT ck_crm_kol_cleanup_item_kol_item CHECK (kol_item_id > 0),
  CONSTRAINT ck_crm_kol_cleanup_item_shop CHECK (shop_id > 0),
  CONSTRAINT ck_crm_kol_cleanup_item_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
  CONSTRAINT ck_crm_kol_cleanup_item_trigger_type
    CHECK (trigger_type IN ('AUTO', 'MANUAL')),
  CONSTRAINT ck_crm_kol_cleanup_item_reason
    CHECK (reason IS NULL OR char_length(reason) <= 200)
);

CREATE INDEX idx_crm_kol_cleanup_item_kol_code
  ON crm_kol_cleanup_item (tenant_id, kol_code, cleanup_time DESC)
  WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_cleanup_item_status
  ON crm_kol_cleanup_item (tenant_id, status, cleanup_time DESC)
  WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_cleanup_item_kol
  ON crm_kol_cleanup_item (kol_id, cleanup_time DESC)
  WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_cleanup_item_bd
  ON crm_kol_cleanup_item (tenant_id, bd_user_id, cleanup_time DESC)
  WHERE is_deleted = FALSE;

COMMENT ON TABLE crm_kol_cleanup_item IS '达人清理明细：记录清理时需删除的微店专属商品及删除结果（ADR-025）';
COMMENT ON COLUMN crm_kol_cleanup_item.id IS '雪花主键';
COMMENT ON COLUMN crm_kol_cleanup_item.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_cleanup_item.kol_id IS '达人 ID（清理时快照，达人逻辑删除后仍保留）';
COMMENT ON COLUMN crm_kol_cleanup_item.kol_code IS '达人编号快照';
COMMENT ON COLUMN crm_kol_cleanup_item.bd_user_id IS '清理时达人所属商务快照，供数据范围过滤；可空';
COMMENT ON COLUMN crm_kol_cleanup_item.kol_item_id IS 'crm_kol_item.id';
COMMENT ON COLUMN crm_kol_cleanup_item.shop_id IS '专属商品所属店铺';
COMMENT ON COLUMN crm_kol_cleanup_item.weidian_item_id IS '微店商品 ID';
COMMENT ON COLUMN crm_kol_cleanup_item.status IS 'PENDING 待删除 / SUCCESS 已成功（含商品不存在） / FAILED 失败';
COMMENT ON COLUMN crm_kol_cleanup_item.trigger_type IS 'AUTO 自动清理 / MANUAL 手动清理';
COMMENT ON COLUMN crm_kol_cleanup_item.reason IS '清理原因（手动必填，≤200）';
COMMENT ON COLUMN crm_kol_cleanup_item.fail_reason IS '删除失败原因（中文，不含令牌与密钥）';
COMMENT ON COLUMN crm_kol_cleanup_item.cleanup_time IS '清理触发时间';
COMMENT ON COLUMN crm_kol_cleanup_item.finish_time IS '删除完成或失败时间';
COMMENT ON COLUMN crm_kol_cleanup_item.create_by IS '创建人；系统自动清理为空';
COMMENT ON COLUMN crm_kol_cleanup_item.create_time IS '创建时间';
COMMENT ON COLUMN crm_kol_cleanup_item.update_by IS '更新人';
COMMENT ON COLUMN crm_kol_cleanup_item.update_time IS '更新时间';
COMMENT ON COLUMN crm_kol_cleanup_item.is_deleted IS '逻辑删除';
