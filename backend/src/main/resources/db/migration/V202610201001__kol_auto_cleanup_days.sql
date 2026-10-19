-- Task 33: automatic cleanup is configured per KOL; NULL means disabled.
ALTER TABLE crm_kol
  ADD COLUMN auto_cleanup_days INTEGER;

ALTER TABLE crm_kol
  ADD CONSTRAINT ck_crm_kol_auto_cleanup_days
    CHECK (auto_cleanup_days IS NULL OR auto_cleanup_days BETWEEN 1 AND 365);

COMMENT ON COLUMN crm_kol.auto_cleanup_days IS
  '自动清理阈值天数；NULL 表示关闭，允许 1～365 天';
