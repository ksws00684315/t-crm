-- Task 17b (ADR-019): external product-link copy jobs for the customer spreadsheet script.

CREATE TABLE crm_copy_job (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  token_id BIGINT NOT NULL,
  job_code VARCHAR(44) NOT NULL,
  caller_platform VARCHAR(64) NOT NULL,
  target_shop_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_count INT NOT NULL,
  success_count INT NOT NULL DEFAULT 0,
  fail_count INT NOT NULL DEFAULT 0,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_copy_job PRIMARY KEY (id),
  CONSTRAINT ck_crm_copy_job_id CHECK (id > 0),
  CONSTRAINT ck_crm_copy_job_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_copy_job_kol CHECK (kol_id > 0),
  CONSTRAINT ck_crm_copy_job_token CHECK (token_id > 0),
  CONSTRAINT ck_crm_copy_job_shop CHECK (target_shop_id > 0),
  CONSTRAINT ck_crm_copy_job_total CHECK (total_count >= 0 AND total_count <= 500),
  CONSTRAINT ck_crm_copy_job_success CHECK (success_count >= 0 AND success_count <= total_count),
  CONSTRAINT ck_crm_copy_job_fail CHECK (fail_count >= 0 AND fail_count <= total_count),
  CONSTRAINT ck_crm_copy_job_status CHECK (status IN ('processing', 'completed', 'failed', 'partial_failed'))
);

CREATE UNIQUE INDEX uk_crm_copy_job_code ON crm_copy_job (job_code);
CREATE INDEX idx_crm_copy_job_tenant_time ON crm_copy_job (tenant_id, create_time DESC);
CREATE INDEX idx_crm_copy_job_status ON crm_copy_job (status, update_time)
  WHERE status = 'processing';

COMMENT ON TABLE crm_copy_job IS '对外复制任务（ADR-019）：客户表格脚本 convert 建任务，后台工人逐条复制';
COMMENT ON COLUMN crm_copy_job.id IS '正数雪花主键';
COMMENT ON COLUMN crm_copy_job.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_copy_job.kol_id IS '达人 crm_kol.id（来自复制令牌）';
COMMENT ON COLUMN crm_copy_job.token_id IS '发起任务时的复制令牌 crm_kol_copy_token.id';
COMMENT ON COLUMN crm_copy_job.job_code IS '128 位随机任务编号（URL 安全），不可猜测；全局唯一';
COMMENT ON COLUMN crm_copy_job.caller_platform IS '调用方标识，脚本传 google_sheet_plugin';
COMMENT ON COLUMN crm_copy_job.target_shop_id IS '复制目标店铺 crm_shop.id';
COMMENT ON COLUMN crm_copy_job.status IS '任务状态：processing / completed / failed / partial_failed';
COMMENT ON COLUMN crm_copy_job.total_count IS '明细条数（去重后），最多 500';
COMMENT ON COLUMN crm_copy_job.success_count IS '成功条数';
COMMENT ON COLUMN crm_copy_job.fail_count IS '失败条数';
COMMENT ON COLUMN crm_copy_job.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_copy_job.update_time IS '最近更新时间（UTC）；作 resultVersion';

CREATE TABLE crm_copy_job_item (
  id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  source_channel VARCHAR(16) NOT NULL,
  source_product_id VARCHAR(32) NOT NULL,
  rule_id VARCHAR(64),
  status VARCHAR(16) NOT NULL,
  target_item_id VARCHAR(32),
  error_message VARCHAR(500),
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_copy_job_item PRIMARY KEY (id),
  CONSTRAINT ck_crm_copy_job_item_id CHECK (id > 0),
  CONSTRAINT ck_crm_copy_job_item_job CHECK (job_id > 0),
  CONSTRAINT ck_crm_copy_job_item_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_copy_job_item_channel CHECK (source_channel ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_crm_copy_job_item_source CHECK (source_product_id ~ '^[0-9]{1,32}$'),
  CONSTRAINT ck_crm_copy_job_item_status CHECK (status IN ('pending', 'success', 'failed')),
  CONSTRAINT ck_crm_copy_job_item_target CHECK (target_item_id IS NULL OR target_item_id ~ '^[0-9]{1,32}$')
);

CREATE UNIQUE INDEX uk_crm_copy_job_item_job_source
  ON crm_copy_job_item (job_id, source_product_id);
CREATE INDEX idx_crm_copy_job_item_job_status ON crm_copy_job_item (job_id, status);

COMMENT ON TABLE crm_copy_job_item IS '对外复制任务明细；同一任务内按来源商品 ID 去重';
COMMENT ON COLUMN crm_copy_job_item.id IS '正数雪花主键';
COMMENT ON COLUMN crm_copy_job_item.job_id IS '所属任务 crm_copy_job.id';
COMMENT ON COLUMN crm_copy_job_item.tenant_id IS '所属公司 sys_tenant.id（与任务一致）';
COMMENT ON COLUMN crm_copy_job_item.source_channel IS '来源渠道，脚本传 WEIDIAN；其他渠道记 failed';
COMMENT ON COLUMN crm_copy_job_item.source_product_id IS '来源微店商品 ID（纯数字字符串）';
COMMENT ON COLUMN crm_copy_job_item.rule_id IS '脚本传入的规则 ID；本系统忽略加价，可空';
COMMENT ON COLUMN crm_copy_job_item.status IS 'pending / success / failed';
COMMENT ON COLUMN crm_copy_job_item.target_item_id IS '复制后的微店商品 ID；成功时有值';
COMMENT ON COLUMN crm_copy_job_item.error_message IS '失败原因（中文，不含令牌或密钥）';
COMMENT ON COLUMN crm_copy_job_item.update_time IS '最近更新时间（UTC）';
