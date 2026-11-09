-- Task 141: the per-request item limit is a system setting since task 138
-- (product-links.max-convert-items, default 500, range 1..5000), but this table still capped
-- total_count at 500. A request the application accepted therefore failed on insert with
-- "violates check constraint ck_crm_copy_job_total" (production 2026-11-09, 1713 items).
-- Keep the constraint aligned with the setting's maximum.
ALTER TABLE crm_copy_job DROP CONSTRAINT ck_crm_copy_job_total;
ALTER TABLE crm_copy_job ADD CONSTRAINT ck_crm_copy_job_total CHECK (total_count >= 0 AND total_count <= 5000);

COMMENT ON COLUMN crm_copy_job.total_count IS '明细条数（去重后），上限与系统配置 product-links.max-convert-items 的最大值一致（5000）';
