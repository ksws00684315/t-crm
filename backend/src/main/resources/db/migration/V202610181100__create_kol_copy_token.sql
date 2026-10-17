-- Task 17a: copy tokens for the customer spreadsheet script (ADR-019). Plaintext is encrypted
-- (token_cipher) and hashed (token_hash) for lookup; the list shows only token_hint.

CREATE TABLE crm_kol_copy_token (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  token_cipher VARCHAR(512) NOT NULL,
  token_hint VARCHAR(32) NOT NULL,
  target_channel VARCHAR(16) NOT NULL,
  target_shop_id BIGINT,
  quota INT NOT NULL,
  used INT NOT NULL DEFAULT 0,
  valid_minutes INT NOT NULL,
  expires_at TIMESTAMPTZ(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  last_used_at TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_kol_copy_token PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_copy_token_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_copy_token_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_copy_token_kol CHECK (kol_id > 0),
  CONSTRAINT ck_crm_kol_copy_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_crm_kol_copy_token_cipher CHECK (token_cipher LIKE 'v1:%'),
  CONSTRAINT ck_crm_kol_copy_token_channel CHECK (target_channel IN ('WEIDIAN')),
  CONSTRAINT ck_crm_kol_copy_token_shop CHECK (target_shop_id IS NULL OR target_shop_id > 0),
  CONSTRAINT ck_crm_kol_copy_token_quota CHECK (quota >= 1 AND quota <= 100000),
  CONSTRAINT ck_crm_kol_copy_token_used CHECK (used >= 0 AND used <= quota),
  CONSTRAINT ck_crm_kol_copy_token_minutes CHECK (valid_minutes >= 5 AND valid_minutes <= 10080),
  CONSTRAINT ck_crm_kol_copy_token_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

-- Global unique hash for authenticate-by-token (cross-company lookup in system scope).
CREATE UNIQUE INDEX uk_crm_kol_copy_token_hash ON crm_kol_copy_token (token_hash)
  WHERE is_deleted = FALSE;

-- List by KOL, newest first (design §5.2).
CREATE INDEX idx_crm_kol_copy_token_kol_time ON crm_kol_copy_token (tenant_id, kol_id, create_time DESC)
  WHERE is_deleted = FALSE;

-- List filters: status + create_time within a company.
CREATE INDEX idx_crm_kol_copy_token_tenant_status_time
  ON crm_kol_copy_token (tenant_id, status, create_time DESC)
  WHERE is_deleted = FALSE;

-- Fuzzy match on the masked hint within a company.
CREATE INDEX idx_crm_kol_copy_token_tenant_hint
  ON crm_kol_copy_token (tenant_id, token_hint)
  WHERE is_deleted = FALSE;

COMMENT ON TABLE crm_kol_copy_token IS '复制令牌（ADR-019）：商务为达人签发，供客户表格脚本调用复制接口；明文加密保存可再次复制，哈希用于认证查找';
COMMENT ON COLUMN crm_kol_copy_token.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol_copy_token.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_kol_copy_token.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_kol_copy_token.token_hash IS '明文的 SHA-256 十六进制；全局唯一，认证时按它查找';
COMMENT ON COLUMN crm_kol_copy_token.token_cipher IS 'SecretCipher 加密的明文（v1:…），供有权限的人再次复制';
COMMENT ON COLUMN crm_kol_copy_token.token_hint IS '脱敏显示值，如 TKN-7FFB****3CDF';
COMMENT ON COLUMN crm_kol_copy_token.target_channel IS '目标渠道；目前仅 WEIDIAN';
COMMENT ON COLUMN crm_kol_copy_token.target_shop_id IS '目标店铺 crm_shop.id；空表示自动分配（规则待 Q1）';
COMMENT ON COLUMN crm_kol_copy_token.quota IS '额度：最多可复制的商品数（1～100000）';
COMMENT ON COLUMN crm_kol_copy_token.used IS '已用数量；0 ≤ used ≤ quota';
COMMENT ON COLUMN crm_kol_copy_token.valid_minutes IS '生成时填写的有效分钟数（5～10080）';
COMMENT ON COLUMN crm_kol_copy_token.expires_at IS '过期时间（UTC）';
COMMENT ON COLUMN crm_kol_copy_token.status IS '入库状态：ACTIVE 有效、REVOKED 已作废；已用尽/已过期由 used 与 expires_at 推出';
COMMENT ON COLUMN crm_kol_copy_token.last_used_at IS '最近一次成功扣减额度的时间（UTC）';
COMMENT ON COLUMN crm_kol_copy_token.create_by IS '生成人 sys_user.id';
COMMENT ON COLUMN crm_kol_copy_token.create_time IS '生成时间（UTC）';
COMMENT ON COLUMN crm_kol_copy_token.update_by IS '最后更新人 sys_user.id';
COMMENT ON COLUMN crm_kol_copy_token.update_time IS '最后更新时间（UTC）';
COMMENT ON COLUMN crm_kol_copy_token.is_deleted IS '逻辑删除：false 有效、true 已删除';
