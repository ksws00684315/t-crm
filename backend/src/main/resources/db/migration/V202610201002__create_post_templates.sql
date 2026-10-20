-- Task 33: per-company defaults for future staff accounts.
CREATE TABLE sys_post_template (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  post_template VARCHAR(16) NOT NULL,
  data_scope VARCHAR(8) NOT NULL,
  permissions JSONB NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL DEFAULT CLOCK_TIMESTAMP(),
  CONSTRAINT pk_sys_post_template PRIMARY KEY (id),
  CONSTRAINT ck_sys_post_template_id CHECK (id > 0),
  CONSTRAINT ck_sys_post_template_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_sys_post_template_type CHECK (post_template IN ('BD', 'OPERATOR')),
  CONSTRAINT ck_sys_post_template_scope CHECK (data_scope IN ('ALL', 'OWN')),
  CONSTRAINT ck_sys_post_template_permissions CHECK (jsonb_typeof(permissions) = 'array')
);

CREATE UNIQUE INDEX uk_sys_post_template_tenant_type
  ON sys_post_template (tenant_id, post_template);

COMMENT ON TABLE sys_post_template IS '公司岗位模板默认权限与数据范围；只影响之后新建的账号';
COMMENT ON COLUMN sys_post_template.id IS '雪花主键';
COMMENT ON COLUMN sys_post_template.tenant_id IS '所属公司';
COMMENT ON COLUMN sys_post_template.post_template IS '岗位模板：BD 商务 / OPERATOR 运营';
COMMENT ON COLUMN sys_post_template.data_scope IS '默认数据范围：ALL 全部 / OWN 本人';
COMMENT ON COLUMN sys_post_template.permissions IS '权限码数组，元素必须属于代码权限目录';
COMMENT ON COLUMN sys_post_template.update_by IS '最后修改人';
COMMENT ON COLUMN sys_post_template.update_time IS '最后修改时间';
