-- Multi-company foundation (task 06c). One migration for the company table, tenant columns,
-- and the indexes whose uniqueness or list order is now per company. Tables are small; adding a
-- column with a constant default on PostgreSQL 17 only updates metadata, and the indexes below
-- are recreated with a brief lock. No foreign keys.

CREATE TABLE sys_tenant (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  remark VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_sys_tenant PRIMARY KEY (id),
  CONSTRAINT ck_sys_tenant_id CHECK (id > 0),
  CONSTRAINT ck_sys_tenant_code CHECK (tenant_code ~ '^[a-z0-9-]{2,32}$'),
  CONSTRAINT ck_sys_tenant_status CHECK (status IN (0, 1))
);
CREATE UNIQUE INDEX uk_sys_tenant_tenant_code ON sys_tenant (tenant_code);
COMMENT ON TABLE sys_tenant IS '公司；雪花 ID，逻辑删除；不提供删除功能；本表自身没有 tenant_id';
COMMENT ON COLUMN sys_tenant.id IS '正数主键；默认公司固定为 1，其余为雪花 ID';
COMMENT ON COLUMN sys_tenant.tenant_code IS '公司编码：^[a-z0-9-]{2,32}$；全表唯一；创建后不可修改';
COMMENT ON COLUMN sys_tenant.name IS '公司名称，1～64 字符';
COMMENT ON COLUMN sys_tenant.status IS '状态：0 停用、1 启用；停用后该公司账号无法登录';
COMMENT ON COLUMN sys_tenant.remark IS '备注；空表示无备注';
COMMENT ON COLUMN sys_tenant.create_by IS '创建人 ID；系统初始化为空';
COMMENT ON COLUMN sys_tenant.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN sys_tenant.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN sys_tenant.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN sys_tenant.is_deleted IS '逻辑删除：false 有效、true 已删除';

INSERT INTO sys_tenant (
  id, tenant_code, name, status, remark, create_by, create_time, update_by, update_time, is_deleted)
VALUES (1, 'default', '默认公司', 1, NULL, NULL, CURRENT_TIMESTAMP(3), NULL, CURRENT_TIMESTAMP(3), FALSE);

ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_role;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_role
  CHECK (role IN ('PLATFORM_ADMIN', 'SUPER_ADMIN', 'OPERATOR', 'FINANCE'));
ALTER TABLE sys_user ADD COLUMN tenant_id BIGINT;
UPDATE sys_user SET tenant_id = 1;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_tenant
  CHECK ((role = 'PLATFORM_ADMIN') = (tenant_id IS NULL));
COMMENT ON COLUMN sys_user.role IS '角色：PLATFORM_ADMIN 平台管理员、SUPER_ADMIN 超级管理员、OPERATOR 运营、FINANCE 财务';
COMMENT ON COLUMN sys_user.tenant_id IS '所属公司（sys_tenant.id）；空值只表示平台管理员，平台管理员不属于任何公司';

ALTER TABLE sys_oper_log ADD COLUMN tenant_id BIGINT;
UPDATE sys_oper_log SET tenant_id = 1;
COMMENT ON COLUMN sys_oper_log.tenant_id IS '所属公司（sys_tenant.id）；空值只表示平台级操作（平台管理员或无法归属到公司的记录）';
CREATE INDEX idx_sys_oper_log_tenant_time ON sys_oper_log (tenant_id, oper_time);

ALTER TABLE crm_kol ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_kol ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_kol.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_kol.email IS '邮箱，小写存储；未删除达人中公司内唯一';
DROP INDEX uk_crm_kol_email;
CREATE UNIQUE INDEX uk_crm_kol_email ON crm_kol (tenant_id, lower(email)) WHERE is_deleted = FALSE;
DROP INDEX idx_crm_kol_create_time;
CREATE INDEX idx_crm_kol_create_time ON crm_kol (tenant_id, create_time, id) WHERE is_deleted = FALSE;
DROP INDEX idx_crm_kol_country;
CREATE INDEX idx_crm_kol_country ON crm_kol (tenant_id, country) WHERE is_deleted = FALSE;

ALTER TABLE crm_kol_bd_history ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_kol_bd_history ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_kol_bd_history.tenant_id IS '所属公司（sys_tenant.id）';

ALTER TABLE crm_shop ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_shop ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_shop.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON TABLE crm_shop IS '微店店铺；每家公司最多 3 个（应用层校验）；雪花 ID，逻辑删除；不含同步凭证（任务 09）';

ALTER TABLE crm_product ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_product ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_product.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_product.product_code IS '原商品编码：^[A-Z0-9_-]{1,16}$，存大写；创建后不可修改，公司内唯一且不复用（含已删除）';
DROP INDEX uk_crm_product_product_code;
CREATE UNIQUE INDEX uk_crm_product_product_code ON crm_product (tenant_id, product_code);
DROP INDEX idx_crm_product_status;
CREATE INDEX idx_crm_product_status ON crm_product (tenant_id, status, product_code) WHERE is_deleted = FALSE;
DROP INDEX idx_crm_product_category;
CREATE INDEX idx_crm_product_category ON crm_product (tenant_id, category, product_code) WHERE is_deleted = FALSE;

ALTER TABLE crm_kol_item ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_kol_item ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_kol_item.tenant_id IS '所属公司（sys_tenant.id）';

ALTER TABLE crm_task ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_task ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_task.tenant_id IS '所属公司（sys_tenant.id）';

ALTER TABLE crm_task_file ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_task_file ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_task_file.tenant_id IS '所属公司（sys_tenant.id）';

ALTER TABLE crm_click_log ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE crm_click_log ALTER COLUMN tenant_id DROP DEFAULT;
COMMENT ON COLUMN crm_click_log.tenant_id IS '所属公司（sys_tenant.id）';
