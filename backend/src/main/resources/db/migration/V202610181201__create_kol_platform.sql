-- Task 25a / ADR-023: KOL ↔ link-template multi-select (default link_template_id stays on crm_kol).

CREATE TABLE crm_kol_platform (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  link_template_id BIGINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_kol_platform PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_platform_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_platform_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_platform_kol CHECK (kol_id > 0),
  CONSTRAINT ck_crm_kol_platform_template CHECK (link_template_id > 0)
);

CREATE UNIQUE INDEX uk_crm_kol_platform_kol_template
  ON crm_kol_platform (kol_id, link_template_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_platform_kol
  ON crm_kol_platform (kol_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_kol_platform_tenant
  ON crm_kol_platform (tenant_id, kol_id) WHERE is_deleted = FALSE;

COMMENT ON TABLE crm_kol_platform IS '达人与代购平台模板的多对多；默认跳转模板仍用 crm_kol.link_template_id';
COMMENT ON COLUMN crm_kol_platform.id IS '雪花主键';
COMMENT ON COLUMN crm_kol_platform.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_platform.kol_id IS '达人 ID';
COMMENT ON COLUMN crm_kol_platform.link_template_id IS '代购平台模板 ID（crm_link_template）';
COMMENT ON COLUMN crm_kol_platform.create_by IS '创建人';
COMMENT ON COLUMN crm_kol_platform.create_time IS '创建时间';
COMMENT ON COLUMN crm_kol_platform.update_by IS '更新人';
COMMENT ON COLUMN crm_kol_platform.update_time IS '更新时间';
COMMENT ON COLUMN crm_kol_platform.is_deleted IS '逻辑删除';
