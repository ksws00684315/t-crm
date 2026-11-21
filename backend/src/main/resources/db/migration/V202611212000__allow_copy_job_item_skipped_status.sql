-- Task 157 (ADR-019): explicit "skipped" item status for copy jobs whose source is already the
-- target KOL's exclusive item (idempotent re-conversion of the same spreadsheet).

ALTER TABLE crm_copy_job_item DROP CONSTRAINT ck_crm_copy_job_item_status;
ALTER TABLE crm_copy_job_item
  ADD CONSTRAINT ck_crm_copy_job_item_status
  CHECK (status IN ('pending', 'success', 'skipped', 'failed'));

COMMENT ON COLUMN crm_copy_job_item.status IS
  'pending / success / skipped（已拥有该商品，幂等跳过）/ failed';
