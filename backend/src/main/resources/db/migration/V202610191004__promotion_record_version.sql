-- Task 29 / ADR-025: version_no / effective_time on promotion records; REVIEW change type.

ALTER TABLE crm_kol_promotion_record
  ADD COLUMN version_no INTEGER,
  ADD COLUMN effective_time TIMESTAMPTZ(3);

ALTER TABLE crm_kol_promotion_record
  DROP CONSTRAINT ck_crm_kol_promotion_record_type;

ALTER TABLE crm_kol_promotion_record
  ADD CONSTRAINT ck_crm_kol_promotion_record_type
    CHECK (change_type IN ('CONFIG', 'STATUS', 'REVIEW')),
  ADD CONSTRAINT ck_crm_kol_promotion_record_version
    CHECK (version_no IS NULL OR version_no >= 1);

COMMENT ON COLUMN crm_kol_promotion_record.change_type IS
  'CONFIG 推广配置 / STATUS 关系状态 / REVIEW 审核通过资料快照';
COMMENT ON COLUMN crm_kol_promotion_record.version_no IS
  '推广配置版本号；仅 CONFIG 有值，每个达人从 1 递增';
COMMENT ON COLUMN crm_kol_promotion_record.effective_time IS
  '版本生效时间（等于保存时间）；仅 CONFIG 有值';
