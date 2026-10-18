-- Task 25a / ADR-023: append-only promotion config / status change records.

CREATE TABLE crm_kol_promotion_record (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  before_json JSONB NOT NULL,
  after_json JSONB NOT NULL,
  remark VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  CONSTRAINT pk_crm_kol_promotion_record PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_promotion_record_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_promotion_record_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_promotion_record_kol CHECK (kol_id > 0),
  CONSTRAINT ck_crm_kol_promotion_record_type CHECK (change_type IN ('CONFIG', 'STATUS')),
  CONSTRAINT ck_crm_kol_promotion_record_before CHECK (jsonb_typeof(before_json) = 'object'),
  CONSTRAINT ck_crm_kol_promotion_record_after CHECK (jsonb_typeof(after_json) = 'object')
);

CREATE INDEX idx_crm_kol_promotion_record_kol
  ON crm_kol_promotion_record (kol_id, create_time DESC, id DESC);
CREATE INDEX idx_crm_kol_promotion_record_tenant
  ON crm_kol_promotion_record (tenant_id, create_time DESC, id DESC);

COMMENT ON TABLE crm_kol_promotion_record IS '达人推广配置与状态修改记录（只追加）';
COMMENT ON COLUMN crm_kol_promotion_record.id IS '雪花主键';
COMMENT ON COLUMN crm_kol_promotion_record.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_promotion_record.kol_id IS '达人 ID';
COMMENT ON COLUMN crm_kol_promotion_record.change_type IS 'CONFIG 推广配置 / STATUS 推广状态';
COMMENT ON COLUMN crm_kol_promotion_record.before_json IS '修改前快照（仅推广配置字段与状态，不含个人信息）';
COMMENT ON COLUMN crm_kol_promotion_record.after_json IS '修改后快照（仅推广配置字段与状态，不含个人信息）';
COMMENT ON COLUMN crm_kol_promotion_record.remark IS '本次修改备注';
COMMENT ON COLUMN crm_kol_promotion_record.create_by IS '操作人（管理员 ID）';
COMMENT ON COLUMN crm_kol_promotion_record.create_time IS '创建时间';
