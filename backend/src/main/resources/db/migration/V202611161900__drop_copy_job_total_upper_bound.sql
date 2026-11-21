-- Task 155: the number of products one copy job may contain is a system setting
-- (product-links.max-convert-items, task 138), so the table must not keep its own hard-coded
-- ceiling. Task 141 raised the ceiling from 500 to 5000 to match the setting's old maximum;
-- the setting can now be raised further (microshop has no per-day add-item limit the customer
-- must honour), so the database keeps only the non-negative invariant and the application
-- enforces the configured limit before inserting.
ALTER TABLE crm_copy_job DROP CONSTRAINT ck_crm_copy_job_total;
ALTER TABLE crm_copy_job ADD CONSTRAINT ck_crm_copy_job_total CHECK (total_count >= 0);

COMMENT ON COLUMN crm_copy_job.total_count IS '明细条数（去重后）；上限由系统配置 product-links.max-convert-items 决定，数据库不再写死';
