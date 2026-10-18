-- Task 25a / ADR-023: per-company idle-KOL cleanup settings (not a generic crm_config).

CREATE TABLE crm_kol_cleanup_config (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  idle_days INT NOT NULL DEFAULT 90,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  CONSTRAINT pk_crm_kol_cleanup_config PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_cleanup_config_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_cleanup_config_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_cleanup_config_idle CHECK (idle_days BETWEEN 7 AND 365)
);

CREATE UNIQUE INDEX uk_crm_kol_cleanup_config_tenant ON crm_kol_cleanup_config (tenant_id);

COMMENT ON TABLE crm_kol_cleanup_config IS '达人清理配置（按公司一行）';
COMMENT ON COLUMN crm_kol_cleanup_config.id IS '雪花主键';
COMMENT ON COLUMN crm_kol_cleanup_config.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_cleanup_config.enabled IS '是否启用自动清理；默认关闭';
COMMENT ON COLUMN crm_kol_cleanup_config.idle_days IS '未出单天数阈值（7～365）';
COMMENT ON COLUMN crm_kol_cleanup_config.create_by IS '创建人';
COMMENT ON COLUMN crm_kol_cleanup_config.create_time IS '创建时间';
COMMENT ON COLUMN crm_kol_cleanup_config.update_by IS '更新人';
COMMENT ON COLUMN crm_kol_cleanup_config.update_time IS '更新时间';
