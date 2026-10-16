-- Task 11: extended KOL profile (WhatsApp, agent template, invite code, sheet URL, register
-- source) and the per-company agent link templates (ADR-019). No foreign keys, as everywhere else.

-- 1. crm_kol: new profile columns; country becomes optional (self-registration, task 12a).
ALTER TABLE crm_kol
  ADD COLUMN whatsapp VARCHAR(32),
  ADD COLUMN link_template_id BIGINT,
  ADD COLUMN invite_code VARCHAR(64),
  ADD COLUMN sheet_url VARCHAR(512),
  ADD COLUMN register_source SMALLINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT ck_crm_kol_whatsapp CHECK (whatsapp ~ '^\+[0-9]{8,15}$'),
  ADD CONSTRAINT ck_crm_kol_invite_code CHECK (invite_code ~ '^[A-Za-z0-9_-]{1,64}$'),
  ADD CONSTRAINT ck_crm_kol_sheet_url CHECK (sheet_url LIKE 'https://docs.google.com/spreadsheets/%'),
  ADD CONSTRAINT ck_crm_kol_register_source CHECK (register_source IN (1, 2));
-- Existing CHECK ck_crm_kol_country keeps two-letter form; NULL passes a CHECK.
ALTER TABLE crm_kol ALTER COLUMN country DROP NOT NULL;
COMMENT ON COLUMN crm_kol.whatsapp IS 'WhatsApp 号码，E.164 格式：+ 加 8～15 位数字；空表示未填写';
COMMENT ON COLUMN crm_kol.link_template_id IS '达人使用的代购平台模板 crm_link_template.id；空表示中转目标为微店直链';
COMMENT ON COLUMN crm_kol.invite_code IS '达人在该代购平台的邀请码，字母数字下划线连字符，1～64 位；代入模板前做 URL 编码';
COMMENT ON COLUMN crm_kol.sheet_url IS '推广表格地址，须以 https://docs.google.com/spreadsheets/ 开头';
COMMENT ON COLUMN crm_kol.register_source IS '注册来源：1 后台录入、2 自助注册';
COMMENT ON COLUMN crm_kol.country IS '国家：ISO 3166-1 两位字母代码，大写；空表示未填写（自助注册可不填，由商务补全）';

-- 2. Agent platform link templates (design §5.2, ADR-019). Super-administered per company;
-- templates are disabled, never deleted. The fixed "T-CRM transit" template is a code constant
-- and is not stored here.
CREATE TABLE crm_link_template (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  template_code VARCHAR(32) NOT NULL,
  template_name VARCHAR(64) NOT NULL,
  template_url VARCHAR(512) NOT NULL,
  channel_code VARCHAR(16) NOT NULL DEFAULT 'WEIDIAN',
  sort_order INT NOT NULL DEFAULT 0,
  status SMALLINT NOT NULL DEFAULT 1,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_link_template PRIMARY KEY (id),
  CONSTRAINT ck_crm_link_template_id CHECK (id > 0),
  CONSTRAINT ck_crm_link_template_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_link_template_code CHECK (template_code ~ '^[a-z0-9-]{2,32}$'),
  CONSTRAINT ck_crm_link_template_url CHECK (template_url ~ '^https://'),
  CONSTRAINT ck_crm_link_template_product_var CHECK (position('{PRODUCT_ID}' IN template_url) > 0),
  CONSTRAINT ck_crm_link_template_channel CHECK (channel_code <> ''),
  CONSTRAINT ck_crm_link_template_sort CHECK (sort_order >= 0),
  CONSTRAINT ck_crm_link_template_status CHECK (status IN (0, 1))
);
-- One code per company among live rows (templates are disabled, not deleted).
CREATE UNIQUE INDEX uk_crm_link_template_code
  ON crm_link_template (tenant_id, template_code) WHERE is_deleted = FALSE;
-- Admin list order (sort_order, id); a company has about twenty templates, no extra index needed.
COMMENT ON TABLE crm_link_template IS '链接模板：各代购平台的商品链接模板，按公司维护；模板只能停用，不能删除；不含固定的 T-CRM 中转模板（代码常量）';
COMMENT ON COLUMN crm_link_template.id IS '正数雪花主键';
COMMENT ON COLUMN crm_link_template.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_link_template.template_code IS '模板编码：小写字母数字连字符，2～32 位；公司内唯一，创建后不可修改';
COMMENT ON COLUMN crm_link_template.template_name IS '模板名称，如 kakobuy';
COMMENT ON COLUMN crm_link_template.template_url IS '模板地址：https 开头，含 {PRODUCT_ID}，可含 {INVITE_CODE}';
COMMENT ON COLUMN crm_link_template.channel_code IS '渠道编码，目前固定 WEIDIAN';
COMMENT ON COLUMN crm_link_template.sort_order IS '排序值，升序；越小越靠前';
COMMENT ON COLUMN crm_link_template.status IS '状态：0 停用、1 启用；停用后使用该模板的达人回退微店直链';
COMMENT ON COLUMN crm_link_template.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_link_template.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_link_template.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_link_template.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_link_template.is_deleted IS '逻辑删除：false 有效、true 已删除（不提供删除功能）';
