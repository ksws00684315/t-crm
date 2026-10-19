-- Task 29 / ADR-025: split promotion_status into review_status + relation_status; add phone and config_version.

ALTER TABLE crm_kol
  ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN relation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PROMOTION',
  ADD COLUMN phone VARCHAR(32),
  ADD COLUMN config_version INTEGER;

-- Map existing promotion_status (ADR-023) into the two new columns.
UPDATE crm_kol SET
  review_status = CASE promotion_status
    WHEN 'PENDING_REVIEW' THEN 'PENDING'
    ELSE 'APPROVED'
  END,
  relation_status = CASE promotion_status
    WHEN 'PENDING_REVIEW' THEN 'PENDING_PROMOTION'
    WHEN 'PENDING_PROMOTION' THEN 'PENDING_PROMOTION'
    WHEN 'PROMOTING' THEN 'PROMOTING'
    WHEN 'ENDED' THEN 'INVALID'
    ELSE 'PENDING_PROMOTION'
  END;

ALTER TABLE crm_kol
  ADD CONSTRAINT ck_crm_kol_review_status
    CHECK (review_status IN ('PENDING', 'APPROVED')),
  ADD CONSTRAINT ck_crm_kol_relation_status
    CHECK (relation_status IN ('PENDING_PROMOTION', 'PROMOTING', 'INVALID')),
  ADD CONSTRAINT ck_crm_kol_phone
    CHECK (phone IS NULL OR phone ~ '^\+[0-9]{8,15}$'),
  ADD CONSTRAINT ck_crm_kol_config_version
    CHECK (config_version IS NULL OR config_version >= 1);

COMMENT ON COLUMN crm_kol.review_status IS
  '审核状态：PENDING 待审核 / APPROVED 已通过；注册后为 PENDING，商务审核后变为 APPROVED，不可再次审核（ADR-025）';
COMMENT ON COLUMN crm_kol.relation_status IS
  '关系状态：PENDING_PROMOTION 待推广 / PROMOTING 推广中 / INVALID 无效；由商务手动维护（ADR-025）';
COMMENT ON COLUMN crm_kol.phone IS
  '手机号，E.164（+ 与 8～15 位数字），可空';
COMMENT ON COLUMN crm_kol.config_version IS
  '当前推广配置版本号（每次保存递增，从 1 起）；尚未配置过为 NULL';
