-- Task 157 (ADR-019): explicit "skipped" item status for copy jobs whose source is already the
-- target KOL's exclusive item (idempotent re-conversion of the same spreadsheet).

ALTER TABLE crm_copy_job_item DROP CONSTRAINT ck_crm_copy_job_item_status;
ALTER TABLE crm_copy_job_item
  ADD CONSTRAINT ck_crm_copy_job_item_status
  CHECK (status IN ('pending', 'success', 'skipped', 'failed'));

COMMENT ON COLUMN crm_copy_job_item.status IS
  'pending / success / skipped（已拥有该商品，幂等跳过）/ failed';

-- Task 157: crm_product's secondary indexes are partial (WHERE is_deleted = FALSE), so the
-- ensureProduct revive lookup for soft-deleted rows needs its own partial index. Tiny: only
-- converged rows qualify, and soft-deleted rows never re-enter the alive indexes.
CREATE INDEX idx_crm_product_shop_deleted
  ON crm_product (shop_id, weidian_item_id)
  WHERE is_deleted = TRUE;

