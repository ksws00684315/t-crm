-- Task 25a / ADR-023: new KOL code format and promotion status.
-- System is not live: do not rewrite existing codes; new CHECK is NOT VALID so demo rows keep
-- the old format until tests rebuild them. New inserts must already match the new pattern.

ALTER TABLE crm_kol DROP CONSTRAINT ck_crm_kol_code;
ALTER TABLE crm_kol
  ADD CONSTRAINT ck_crm_kol_code CHECK (kol_code ~ '^KOL-[A-Z]{3}[0-9]{3,8}$') NOT VALID;

COMMENT ON COLUMN crm_kol.kol_code IS
  '达人编号：KOL- + 3 位大写字母 + 3～8 位序号；创建后不可修改，全表唯一且不复用（含已删除）';
COMMENT ON SEQUENCE crm_kol_code_seq IS
  '达人编号顺序号；序号至少 3 位补零，字母由应用随机；已占用的号码由应用跳过，号码不复用';

ALTER TABLE crm_kol
  ADD COLUMN promotion_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
  ADD COLUMN landing_url VARCHAR(512);

-- Existing demo rows stay promotable (system not live yet).
UPDATE crm_kol SET promotion_status = 'PROMOTING';

ALTER TABLE crm_kol
  ADD CONSTRAINT ck_crm_kol_promotion_status
    CHECK (promotion_status IN ('PENDING_REVIEW', 'PENDING_PROMOTION', 'PROMOTING', 'ENDED')),
  ADD CONSTRAINT ck_crm_kol_landing_url
    CHECK (landing_url IS NULL OR landing_url ~ '^https://');

COMMENT ON COLUMN crm_kol.promotion_status IS
  '推广状态：PENDING_REVIEW 待审核 / PENDING_PROMOTION 待推广 / PROMOTING 推广中 / ENDED 合作结束；由商务手动修改';
COMMENT ON COLUMN crm_kol.landing_url IS
  '达人专属落地页地址，https，≤512；推广配置必填项之一';
