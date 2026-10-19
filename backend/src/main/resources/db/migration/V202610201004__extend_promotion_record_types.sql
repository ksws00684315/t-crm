-- Task 33: per-KOL automatic cleanup changes are part of the append-only promotion history.
ALTER TABLE crm_kol_promotion_record DROP CONSTRAINT ck_crm_kol_promotion_record_type;
ALTER TABLE crm_kol_promotion_record
  ADD CONSTRAINT ck_crm_kol_promotion_record_type
    CHECK (change_type IN ('CONFIG', 'STATUS', 'REVIEW', 'AUTO_CLEANUP'));
COMMENT ON COLUMN crm_kol_promotion_record.change_type IS
  'CONFIG 推广配置 / STATUS 关系状态 / REVIEW 审核 / AUTO_CLEANUP 单达人自动清理设置';
