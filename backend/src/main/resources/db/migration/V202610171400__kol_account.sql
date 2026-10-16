-- KOL accounts: password login, activation and one-time tokens (task 12a, design §3.4, §4.2).
-- The four columns are only written through explicit column whitelists in the kol module, so the
-- entity marks them NEVER for entity updates.

ALTER TABLE crm_kol ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE crm_kol ADD COLUMN account_status SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE crm_kol ADD COLUMN credential_version INT NOT NULL DEFAULT 0;
ALTER TABLE crm_kol ADD COLUMN last_login_time TIMESTAMPTZ(3);
ALTER TABLE crm_kol ADD CONSTRAINT ck_crm_kol_account_status CHECK (account_status IN (0, 1));
ALTER TABLE crm_kol ADD CONSTRAINT ck_crm_kol_account_password
  CHECK (account_status = 0 OR password_hash IS NOT NULL);
COMMENT ON COLUMN crm_kol.password_hash IS '登录密码的 BCrypt 哈希；未激活时为空（见 ck_crm_kol_account_password），激活或重置时写入';
COMMENT ON COLUMN crm_kol.account_status IS '账号状态：0 未激活（后台录入后尚未设置密码）、1 已激活';
COMMENT ON COLUMN crm_kol.credential_version IS '凭据版本：改密或重置时加 1，令牌会话绑定签发时的版本，不一致即失效';
COMMENT ON COLUMN crm_kol.last_login_time IS '最近一次登录时间（UTC）；空表示从未登录';

CREATE TABLE crm_kol_auth_token (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  purpose VARCHAR(8) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ(3) NOT NULL,
  used_at TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_kol_auth_token PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_auth_token_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_auth_token_purpose CHECK (purpose IN ('INVITE', 'RESET'))
);
-- Only the SHA-256 of the random token is stored; the raw value exists only in the email link.
CREATE UNIQUE INDEX uk_crm_kol_auth_token_token_hash ON crm_kol_auth_token (token_hash);
-- Resending invalidates the previous unused token of the same purpose for the KOL.
CREATE INDEX idx_crm_kol_auth_token_kol ON crm_kol_auth_token (kol_id, purpose);
COMMENT ON TABLE crm_kol_auth_token IS '达人一次性凭据令牌（邀请激活、重置密码）；只追加，使用后置 used_at，不提供删除与逻辑删除，故无修改类字段';
COMMENT ON COLUMN crm_kol_auth_token.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol_auth_token.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_kol_auth_token.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_kol_auth_token.purpose IS '用途：INVITE 邀请激活（72 小时有效）、RESET 重置密码（30 分钟有效）';
COMMENT ON COLUMN crm_kol_auth_token.token_hash IS '令牌原文的 SHA-256 十六进制摘要；原文只出现在邮件链接中，不入库';
COMMENT ON COLUMN crm_kol_auth_token.expires_at IS '过期时间（UTC）';
COMMENT ON COLUMN crm_kol_auth_token.used_at IS '使用或作废时间（UTC）；空表示未使用';
COMMENT ON COLUMN crm_kol_auth_token.create_time IS '创建时间（UTC）';
