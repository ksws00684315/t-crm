-- Company edition (ADR-038, task 143 §2.1). Every existing company is the full distribution
-- product. The column is added with a constant default so PostgreSQL 17 only updates metadata; the
-- default is then dropped, so a new company must name its edition (the API requires it too).

ALTER TABLE sys_tenant ADD COLUMN edition VARCHAR(16) NOT NULL DEFAULT 'DISTRIBUTION';
ALTER TABLE sys_tenant ALTER COLUMN edition DROP DEFAULT;
ALTER TABLE sys_tenant ADD CONSTRAINT ck_sys_tenant_edition
  CHECK (edition IN ('DISTRIBUTION', 'KOL_LIBRARY'));
COMMENT ON COLUMN sys_tenant.edition IS
  '公司版本：DISTRIBUTION 分销完整版（现有全部功能）、KOL_LIBRARY 达人库版（达人库与账号管理）；新建公司时选择，创建后不可修改（ADR-038）';