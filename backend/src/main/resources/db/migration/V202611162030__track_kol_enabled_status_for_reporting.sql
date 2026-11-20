CREATE TABLE crm_kol_reporting_status (
  event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL,
  baseline BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_crm_kol_reporting_status_kol
  ON crm_kol_reporting_status (tenant_id, kol_id, changed_at, event_id);
COMMENT ON TABLE crm_kol_reporting_status IS '达人启用状态事件，用于经营分析的区间有效人数';
COMMENT ON COLUMN crm_kol_reporting_status.event_id IS '同一时点内的状态事件序号';
COMMENT ON COLUMN crm_kol_reporting_status.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_reporting_status.kol_id IS '达人 ID';
COMMENT ON COLUMN crm_kol_reporting_status.enabled IS '此事件起是否有效';
COMMENT ON COLUMN crm_kol_reporting_status.changed_at IS '状态生效时间';
COMMENT ON COLUMN crm_kol_reporting_status.baseline IS '存量基线，之前的状态无法由此事件推断';

-- Keep the baseline snapshot consistent with concurrent status changes.
LOCK TABLE crm_kol IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO crm_kol_reporting_status (tenant_id, kol_id, enabled, changed_at, baseline)
SELECT tenant_id, id, status = 1 AND NOT is_deleted, statement_timestamp(), TRUE
FROM crm_kol;
