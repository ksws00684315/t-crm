-- Multi-company membership (task 21a, ADR-022). sys_user becomes the person: login name, password,
-- name, e-mail and the account-wide status. Role, data scope, post template and the company-level
-- status move to sys_user_tenant, one row per account and company. Permission codes are granted per
-- company. Every existing company account gets exactly one membership in its former company;
-- platform administrators get none. The account tables hold a few rows, so the ACCESS EXCLUSIVE
-- locks taken by the ALTER statements below are held only briefly. No foreign keys.

CREATE TABLE sys_user_tenant (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  post_template VARCHAR(16),
  data_scope VARCHAR(3) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  last_login_time TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_sys_user_tenant PRIMARY KEY (id),
  CONSTRAINT ck_sys_user_tenant_id CHECK (id > 0),
  CONSTRAINT ck_sys_user_tenant_role CHECK (role IN ('SUPER_ADMIN', 'STAFF')),
  CONSTRAINT ck_sys_user_tenant_scope CHECK (data_scope IN ('ALL', 'OWN')),
  CONSTRAINT ck_sys_user_tenant_own CHECK (data_scope = 'ALL' OR role = 'STAFF'),
  CONSTRAINT ck_sys_user_tenant_post_template CHECK (post_template IN ('OPERATOR', 'BD')),
  CONSTRAINT ck_sys_user_tenant_status CHECK (status IN (0, 1))
);
CREATE UNIQUE INDEX uk_sys_user_tenant_user_tenant
  ON sys_user_tenant (user_id, tenant_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_sys_user_tenant_tenant_create_time
  ON sys_user_tenant (tenant_id, create_time, id) WHERE is_deleted = FALSE;
COMMENT ON TABLE sys_user_tenant IS '账号的公司成员关系；一个账号在一家公司最多一条有效记录；角色、岗位模板、数据范围、启停按公司分别设置；雪花 ID，逻辑删除';
COMMENT ON COLUMN sys_user_tenant.id IS '正数主键；迁移数据用行号，应用写入用雪花 ID';
COMMENT ON COLUMN sys_user_tenant.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN sys_user_tenant.user_id IS '账号（sys_user.id）';
COMMENT ON COLUMN sys_user_tenant.role IS '在该公司的角色：SUPER_ADMIN 超级管理员、STAFF 子账号';
COMMENT ON COLUMN sys_user_tenant.post_template IS '在该公司创建或加入时选择的岗位模板，仅用于显示：OPERATOR 运营、BD 商务；空表示未选择';
COMMENT ON COLUMN sys_user_tenant.data_scope IS '在该公司的数据范围：ALL 全部、OWN 本人（仅子账号）';
COMMENT ON COLUMN sys_user_tenant.status IS '在该公司的状态：0 停用、1 启用；只影响该公司';
COMMENT ON COLUMN sys_user_tenant.last_login_time IS '最近一次登录或切换进入该公司的时间（UTC）；空表示尚未进入';
COMMENT ON COLUMN sys_user_tenant.create_by IS '创建人 ID；迁移写入为空';
COMMENT ON COLUMN sys_user_tenant.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN sys_user_tenant.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN sys_user_tenant.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN sys_user_tenant.is_deleted IS '逻辑删除：false 有效、true 已删除';

-- Backfill while sys_user still carries the company columns. Deleted accounts keep a deleted
-- membership, so history stays attributable.
INSERT INTO sys_user_tenant (
  id, tenant_id, user_id, role, post_template, data_scope, status, last_login_time,
  create_by, create_time, update_by, update_time, is_deleted)
SELECT row_number() OVER (ORDER BY id),
       tenant_id,
       id,
       role,
       post_template,
       data_scope,
       status,
       last_login_time,
       create_by,
       create_time,
       update_by,
       update_time,
       is_deleted
FROM sys_user
WHERE role <> 'PLATFORM_ADMIN';

-- Every disable so far was company-level (company administrators had no other kind), and it has
-- just been copied to the membership. The account itself starts enabled; from now on only the
-- platform administrator disables a whole account.
UPDATE sys_user SET status = 1 WHERE role <> 'PLATFORM_ADMIN' AND status <> 1;

-- Codes belong to the company the account belonged to.
UPDATE sys_user_permission p
SET tenant_id = u.tenant_id
FROM sys_user u
WHERE u.id = p.user_id AND u.tenant_id IS NOT NULL AND p.tenant_id <> u.tenant_id;
DROP INDEX uk_sys_user_permission_user_code;
CREATE UNIQUE INDEX uk_sys_user_permission_user_tenant_code
  ON sys_user_permission (user_id, tenant_id, permission_code);
COMMENT ON TABLE sys_user_permission IS '子账号在某家公司的权限；一行一个权限码；同一账号在不同公司的权限互不影响；只追加与删除，省略修改字段与逻辑删除';
COMMENT ON COLUMN sys_user_permission.tenant_id IS '权限所在公司（sys_tenant.id）';

ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_tenant;
ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_own;
ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_scope;
ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_post_template;
ALTER TABLE sys_user DROP CONSTRAINT ck_sys_user_role;
UPDATE sys_user SET role = 'MEMBER' WHERE role <> 'PLATFORM_ADMIN';
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_role CHECK (role IN ('PLATFORM_ADMIN', 'MEMBER'));
ALTER TABLE sys_user DROP COLUMN data_scope;
ALTER TABLE sys_user DROP COLUMN post_template;
ALTER TABLE sys_user RENAME COLUMN tenant_id TO last_tenant_id;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_last_tenant
  CHECK (role = 'MEMBER' OR last_tenant_id IS NULL);
COMMENT ON TABLE sys_user IS '后台账号（人）；不属于某一家公司，公司身份见 sys_user_tenant；雪花 ID，逻辑删除';
COMMENT ON COLUMN sys_user.role IS '账号类型：PLATFORM_ADMIN 平台管理员、MEMBER 公司账号（在各公司的角色见 sys_user_tenant）';
COMMENT ON COLUMN sys_user.status IS '整个账号的状态：0 停用、1 启用；只有平台管理员能修改';
COMMENT ON COLUMN sys_user.last_login_time IS '最近登录时间（UTC），不分公司；空表示尚未登录';
COMMENT ON COLUMN sys_user.last_tenant_id IS '上次所在的公司（sys_tenant.id），只用于登录时选默认公司；平台管理员为空';
