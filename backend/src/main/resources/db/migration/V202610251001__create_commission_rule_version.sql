-- Task 62 / ADR-032 §4: company-level settlement config versions (11.03).

CREATE TABLE crm_commission_rule_version (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  version_no INTEGER NOT NULL,
  currency VARCHAR(8) NOT NULL,
  minimum_withdrawal_cny NUMERIC(14,2) NOT NULL,
  channel_rules JSONB NOT NULL,
  actor_id BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_rule_version PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_rule_version_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_rule_version_no CHECK (version_no >= 1),
  CONSTRAINT ck_crm_commission_rule_version_currency CHECK (currency = 'CNY'),
  CONSTRAINT ck_crm_commission_rule_version_minimum CHECK (minimum_withdrawal_cny >= 0),
  CONSTRAINT ck_crm_commission_rule_version_rules CHECK (jsonb_typeof(channel_rules) = 'object')
);
CREATE UNIQUE INDEX uk_crm_commission_rule_version_no ON crm_commission_rule_version (tenant_id, version_no);
CREATE INDEX idx_crm_commission_rule_version_latest ON crm_commission_rule_version (tenant_id, version_no DESC);
COMMENT ON TABLE crm_commission_rule_version IS '公司结算配置版本（只追加，任务 62 / ADR-032 §4，11.03）：结算币种、最低提现额与渠道冻结规则；释放时间按订单支付时刻的版本快照；无修改与删除';
COMMENT ON COLUMN crm_commission_rule_version.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_rule_version.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_commission_rule_version.version_no IS '公司内递增版本号，从 1 开始';
COMMENT ON COLUMN crm_commission_rule_version.currency IS '结算币种，固定 CNY';
COMMENT ON COLUMN crm_commission_rule_version.minimum_withdrawal_cny IS '最低提现金额，元；默认 200.00';
COMMENT ON COLUMN crm_commission_rule_version.channel_rules IS '渠道冻结规则 JSONB，如 {"WEIDIAN":{"basis":"SETTLE_TIME","days":7}}；basis 为 SETTLE_TIME 或 COMPLETED_PLUS_DAYS，days 0～365';
COMMENT ON COLUMN crm_commission_rule_version.actor_id IS '保存人 sys_user.id；系统写入为空';
COMMENT ON COLUMN crm_commission_rule_version.create_time IS '版本生效（创建）时间 UTC';
