-- Weidian credentials and per-shop authorization. No foreign keys by project database convention.
CREATE TABLE crm_weidian_app (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  app_key VARCHAR(64) NOT NULL,
  app_secret_cipher TEXT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_weidian_app PRIMARY KEY (id),
  CONSTRAINT ck_crm_weidian_app_id CHECK (id > 0),
  CONSTRAINT ck_crm_weidian_app_tenant_id CHECK (tenant_id > 0)
);
CREATE UNIQUE INDEX uk_crm_weidian_app_tenant ON crm_weidian_app (tenant_id);
COMMENT ON TABLE crm_weidian_app IS '公司级微店服务型应用配置；secret 使用 AES-256-GCM 密文保存；雪花 ID、逻辑删除';
COMMENT ON COLUMN crm_weidian_app.id IS '雪花主键';
COMMENT ON COLUMN crm_weidian_app.tenant_id IS '所属公司（sys_tenant.id）；每家公司最多一份服务型应用';
COMMENT ON COLUMN crm_weidian_app.app_key IS '微店开放平台 appkey；不是密钥';
COMMENT ON COLUMN crm_weidian_app.app_secret_cipher IS '微店应用 secret 密文；SecretCipher v1 格式';
COMMENT ON COLUMN crm_weidian_app.create_by IS '创建人 ID';
COMMENT ON COLUMN crm_weidian_app.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_weidian_app.update_by IS '最后修改人 ID';
COMMENT ON COLUMN crm_weidian_app.update_time IS '最后修改时间（UTC）';
COMMENT ON COLUMN crm_weidian_app.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_weidian_shop_auth (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  auth_mode VARCHAR(16) NOT NULL,
  self_app_key VARCHAR(64),
  self_app_secret_cipher TEXT,
  openid VARCHAR(64),
  access_token_cipher TEXT,
  refresh_token_cipher TEXT,
  access_expires_at TIMESTAMPTZ(3),
  refresh_expires_at TIMESTAMPTZ(3),
  status VARCHAR(16) NOT NULL,
  authorized_at TIMESTAMPTZ(3),
  authorized_by BIGINT,
  last_refresh_at TIMESTAMPTZ(3),
  last_check_at TIMESTAMPTZ(3),
  last_check_ok BOOLEAN,
  last_error VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_weidian_shop_auth PRIMARY KEY (id),
  CONSTRAINT ck_crm_weidian_shop_auth_id CHECK (id > 0),
  CONSTRAINT ck_crm_weidian_shop_auth_tenant_id CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_weidian_shop_auth_shop_id CHECK (shop_id > 0),
  CONSTRAINT ck_crm_weidian_shop_auth_mode CHECK (auth_mode IN ('SELF_USE', 'SERVICE')),
  CONSTRAINT ck_crm_weidian_shop_auth_self_credentials CHECK (
    (auth_mode = 'SELF_USE' AND self_app_key IS NOT NULL AND self_app_secret_cipher IS NOT NULL)
    OR (auth_mode = 'SERVICE' AND self_app_key IS NULL AND self_app_secret_cipher IS NULL)
  ),
  CONSTRAINT ck_crm_weidian_shop_auth_status CHECK (
    status IN ('UNAUTHORIZED', 'AUTHORIZED', 'EXPIRED', 'FAILED')
  )
);
CREATE UNIQUE INDEX uk_crm_weidian_shop_auth_shop ON crm_weidian_shop_auth (shop_id);
CREATE INDEX idx_crm_weidian_shop_auth_renewal
  ON crm_weidian_shop_auth (tenant_id, status, access_expires_at)
  WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_weidian_shop_auth IS '微店店铺接入状态与令牌密文；雪花 ID、逻辑删除；不设置外键';
COMMENT ON COLUMN crm_weidian_shop_auth.id IS '雪花主键';
COMMENT ON COLUMN crm_weidian_shop_auth.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_weidian_shop_auth.shop_id IS 'CRM 店铺 ID；全系统唯一，每个店铺最多一行';
COMMENT ON COLUMN crm_weidian_shop_auth.auth_mode IS '接入模式：SELF_USE 自用型、SERVICE 服务型';
COMMENT ON COLUMN crm_weidian_shop_auth.self_app_key IS '自用型微店应用 appkey；仅 SELF_USE 非空';
COMMENT ON COLUMN crm_weidian_shop_auth.self_app_secret_cipher IS '自用型应用 secret 密文；仅 SELF_USE 非空';
COMMENT ON COLUMN crm_weidian_shop_auth.openid IS '服务型授权返回的微店店铺 ID';
COMMENT ON COLUMN crm_weidian_shop_auth.access_token_cipher IS '访问令牌密文；SecretCipher v1 格式';
COMMENT ON COLUMN crm_weidian_shop_auth.refresh_token_cipher IS '刷新令牌密文；自用型为空';
COMMENT ON COLUMN crm_weidian_shop_auth.access_expires_at IS '访问令牌过期时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.refresh_expires_at IS '刷新令牌过期时间（UTC）；按接口返回值及 30 天边界计算';
COMMENT ON COLUMN crm_weidian_shop_auth.status IS '接入状态：UNAUTHORIZED 未接入、AUTHORIZED 正常、EXPIRED 需重新接入、FAILED 校验或续期失败';
COMMENT ON COLUMN crm_weidian_shop_auth.authorized_at IS '最近一次令牌获取或授权成功时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.authorized_by IS '最近一次授权或凭证录入的操作人 ID';
COMMENT ON COLUMN crm_weidian_shop_auth.last_refresh_at IS '最近一次成功获取或刷新令牌时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.last_check_at IS '最近一次连通性检查时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.last_check_ok IS '最近一次连通性检查结果；NULL 表示未检查';
COMMENT ON COLUMN crm_weidian_shop_auth.last_error IS '英文错误摘要；不得包含令牌、secret 或授权码';
COMMENT ON COLUMN crm_weidian_shop_auth.create_by IS '创建人 ID';
COMMENT ON COLUMN crm_weidian_shop_auth.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.update_by IS '最后修改人 ID';
COMMENT ON COLUMN crm_weidian_shop_auth.update_time IS '最后修改时间（UTC）';
COMMENT ON COLUMN crm_weidian_shop_auth.is_deleted IS '逻辑删除：false 有效、true 已删除';
