CREATE SEQUENCE crm_kol_code_seq AS BIGINT START WITH 1 MINVALUE 1 MAXVALUE 99999999 NO CYCLE;
COMMENT ON SEQUENCE crm_kol_code_seq IS '达人编号顺序号；KOL + 至少 4 位补零，已占用的号码由应用跳过，号码不复用';

CREATE TABLE crm_kol (
  id BIGINT NOT NULL,
  kol_code VARCHAR(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  email VARCHAR(128) NOT NULL,
  country VARCHAR(2) NOT NULL,
  social_accounts JSONB NOT NULL DEFAULT '[]'::jsonb,
  bd_user_id BIGINT,
  default_rate NUMERIC(6,4),
  status SMALLINT NOT NULL DEFAULT 1,
  remark VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_kol PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_code CHECK (kol_code ~ '^KOL[0-9]{4,8}$'),
  CONSTRAINT ck_crm_kol_email CHECK (email = lower(email)),
  CONSTRAINT ck_crm_kol_country CHECK (country ~ '^[A-Z]{2}$'),
  CONSTRAINT ck_crm_kol_social CHECK (jsonb_typeof(social_accounts) = 'array'),
  CONSTRAINT ck_crm_kol_rate CHECK (default_rate >= 0 AND default_rate <= 1),
  CONSTRAINT ck_crm_kol_status CHECK (status IN (0, 1))
);
-- Codes are never reused, so uniqueness also covers logically deleted rows.
CREATE UNIQUE INDEX uk_crm_kol_kol_code ON crm_kol (kol_code);
CREATE UNIQUE INDEX uk_crm_kol_email ON crm_kol (lower(email)) WHERE is_deleted = FALSE;
-- Default list order (create_time DESC, id DESC).
CREATE INDEX idx_crm_kol_create_time ON crm_kol (create_time, id) WHERE is_deleted = FALSE;
-- OWN data scope and the bdUserId filter, in list order.
CREATE INDEX idx_crm_kol_bd_user ON crm_kol (bd_user_id, create_time, id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_country ON crm_kol (country) WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_kol IS '达人；雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_kol.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol.kol_code IS '达人编号：KOL + 4～8 位数字；创建后不可修改，全表唯一且不复用（含已删除）';
COMMENT ON COLUMN crm_kol.name IS '姓名';
COMMENT ON COLUMN crm_kol.email IS '邮箱，小写存储；未删除达人中唯一';
COMMENT ON COLUMN crm_kol.country IS '国家：ISO 3166-1 两位字母代码，大写';
COMMENT ON COLUMN crm_kol.social_accounts IS '社媒账号 JSON 数组 [{platform, handle, url, followers}]，最多 10 个；url 为空表示未填写，followers 为空表示未知；无社媒账号时为 []';
COMMENT ON COLUMN crm_kol.bd_user_id IS '当前负责商务 sys_user.id（启用的 OPERATOR + OWN 账号）；空表示未分配商务';
COMMENT ON COLUMN crm_kol.default_rate IS '默认佣金比例，小数 0～1（0.1550 表示 15.5%）；空表示未设置';
COMMENT ON COLUMN crm_kol.status IS '状态：0 停用、1 启用';
COMMENT ON COLUMN crm_kol.remark IS '合作备注；空表示无备注';
COMMENT ON COLUMN crm_kol.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_kol.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_kol.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_kol_bd_history (
  id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  bd_user_id BIGINT,
  start_time TIMESTAMPTZ(3) NOT NULL,
  end_time TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_kol_bd_history PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_bd_history_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_bd_history_period CHECK (end_time IS NULL OR end_time >= start_time)
);
-- At most one current (open) ownership period per KOL.
CREATE UNIQUE INDEX uk_crm_kol_bd_history_current ON crm_kol_bd_history (kol_id) WHERE end_time IS NULL;
-- Detail page: history of one KOL in time order.
CREATE INDEX idx_crm_kol_bd_history_kol ON crm_kol_bd_history (kol_id, start_time);
COMMENT ON TABLE crm_kol_bd_history IS '达人商务归属历史；每次更换商务（含首次分配、从有到空、从空到有）关闭当前记录并新增一条；雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_kol_bd_history.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol_bd_history.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_kol_bd_history.bd_user_id IS '负责商务 sys_user.id；空表示该期间未分配商务';
COMMENT ON COLUMN crm_kol_bd_history.start_time IS '归属开始时间（UTC）';
COMMENT ON COLUMN crm_kol_bd_history.end_time IS '归属结束时间（UTC）；空表示当前归属';
COMMENT ON COLUMN crm_kol_bd_history.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_bd_history.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_kol_bd_history.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_bd_history.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_kol_bd_history.is_deleted IS '逻辑删除：false 有效、true 已删除';
