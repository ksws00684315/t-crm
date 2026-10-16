-- Configurable staff permissions (task 10a, ADR-018). Converts OPERATOR / FINANCE before the new
-- role check is added, so existing development and test databases can upgrade. Application inserts
-- use snowflake IDs; rows created here use row_number() starting at 1, which cannot collide with
-- those snowflake values. The table is append-and-delete only.

CREATE TABLE sys_user_permission (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  permission_code VARCHAR(48) NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_sys_user_permission PRIMARY KEY (id),
  CONSTRAINT ck_sys_user_permission_id CHECK (id > 0)
);
CREATE UNIQUE INDEX uk_sys_user_permission_user_code
  ON sys_user_permission (user_id, permission_code);
CREATE INDEX idx_sys_user_permission_tenant_code
  ON sys_user_permission (tenant_id, permission_code);
COMMENT ON TABLE sys_user_permission IS '子账号权限；一行一个权限码；只追加与删除，省略修改字段与逻辑删除';
COMMENT ON COLUMN sys_user_permission.id IS '正数主键；迁移数据用行号，应用写入用雪花 ID';
COMMENT ON COLUMN sys_user_permission.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN sys_user_permission.user_id IS '子账号（sys_user.id）';
COMMENT ON COLUMN sys_user_permission.permission_code IS '权限码，与代码中的 Permission 枚举一致';
COMMENT ON COLUMN sys_user_permission.create_by IS '创建人 ID；迁移写入为空';
COMMENT ON COLUMN sys_user_permission.create_time IS '创建时间（UTC）';

ALTER TABLE sys_user ADD COLUMN post_template VARCHAR(16);
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_post_template
  CHECK (post_template IN ('OPERATOR', 'BD'));
COMMENT ON COLUMN sys_user.post_template IS '创建时选择的岗位模板，仅用于显示：OPERATOR 运营、BD 商务；空表示未选择';

-- Grant while the old role values still identify the source accounts.
INSERT INTO sys_user_permission (id, tenant_id, user_id, permission_code, create_by, create_time)
SELECT row_number() OVER (ORDER BY user_id, permission_code),
       tenant_id,
       user_id,
       permission_code,
       NULL,
       CURRENT_TIMESTAMP(3)
FROM (
  SELECT u.id AS user_id, u.tenant_id, p.code AS permission_code
  FROM sys_user u
  CROSS JOIN (
    VALUES
      ('kol:view'),
      ('product:view'),
      ('product:edit'),
      ('product:status'),
      ('order:view'),
      ('order:manage'),
      ('dashboard:shop'),
      ('dashboard:kol'),
      ('data:export')
  ) AS p(code)
  WHERE u.role = 'OPERATOR' AND u.data_scope = 'ALL'
  UNION ALL
  SELECT u.id, u.tenant_id, p.code
  FROM sys_user u
  CROSS JOIN (
    VALUES
      ('kol:view'),
      ('kol:edit'),
      ('kol:register-link'),
      ('kol:copy-token'),
      ('product:view'),
      ('order:view'),
      ('commission:view'),
      ('payout-account:review'),
      ('withdrawal:review'),
      ('prepay:apply'),
      ('dashboard:team'),
      ('dashboard:kol'),
      ('data:export')
  ) AS p(code)
  WHERE u.role = 'OPERATOR' AND u.data_scope = 'OWN'
  UNION ALL
  SELECT u.id, u.tenant_id, p.code
  FROM sys_user u
  CROSS JOIN (
    VALUES
      ('kol:view'),
      ('product:view'),
      ('order:view'),
      ('commission:view'),
      ('dashboard:shop'),
      ('dashboard:team'),
      ('dashboard:kol'),
      ('data:export')
  ) AS p(code)
  WHERE u.role = 'FINANCE'
) AS granted;

ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_own;
ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_role;

UPDATE sys_user
SET role = 'STAFF', post_template = 'OPERATOR'
WHERE role = 'OPERATOR' AND data_scope = 'ALL';

UPDATE sys_user
SET role = 'STAFF', post_template = 'BD'
WHERE role = 'OPERATOR' AND data_scope = 'OWN';

UPDATE sys_user
SET role = 'STAFF', post_template = NULL, data_scope = 'ALL'
WHERE role = 'FINANCE';

ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_role
  CHECK (role IN ('PLATFORM_ADMIN', 'SUPER_ADMIN', 'STAFF'));
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_own
  CHECK (data_scope = 'ALL' OR role = 'STAFF');
COMMENT ON COLUMN sys_user.role IS '角色：PLATFORM_ADMIN 平台管理员、SUPER_ADMIN 超级管理员、STAFF 子账号';
COMMENT ON COLUMN sys_user.data_scope IS '数据范围：ALL 全部、OWN 本人（仅子账号）';
