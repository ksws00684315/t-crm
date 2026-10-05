CREATE TABLE sys_user (
  id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  email VARCHAR(254),
  role VARCHAR(16) NOT NULL,
  data_scope VARCHAR(3) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  last_login_time TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_sys_user PRIMARY KEY (id),
  CONSTRAINT ck_sys_user_id CHECK (id > 0),
  CONSTRAINT ck_sys_user_role CHECK (role IN ('SUPER_ADMIN', 'OPERATOR', 'FINANCE')),
  CONSTRAINT ck_sys_user_scope CHECK (data_scope IN ('ALL', 'OWN')),
  CONSTRAINT ck_sys_user_status CHECK (status IN (0, 1)),
  CONSTRAINT ck_sys_user_own CHECK (data_scope = 'ALL' OR role = 'OPERATOR')
);
-- Preserve case-insensitive account identity, including logically deleted accounts.
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (lower(username));
COMMENT ON TABLE sys_user IS '后台用户；雪花 ID，逻辑删除';
COMMENT ON COLUMN sys_user.id IS '正数雪花主键';
COMMENT ON COLUMN sys_user.username IS '登录名；ASCII，忽略大小写唯一';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 密码哈希，禁止对外输出';
COMMENT ON COLUMN sys_user.nickname IS '显示名称';
COMMENT ON COLUMN sys_user.email IS '电子邮箱；空表示未填写';
COMMENT ON COLUMN sys_user.role IS '角色：SUPER_ADMIN 超级管理员、OPERATOR 运营、FINANCE 财务';
COMMENT ON COLUMN sys_user.data_scope IS '数据范围：ALL 全部、OWN 本人（仅运营）';
COMMENT ON COLUMN sys_user.status IS '状态：0 停用、1 启用';
COMMENT ON COLUMN sys_user.last_login_time IS '最近登录时间（UTC）；空表示尚未登录';
COMMENT ON COLUMN sys_user.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN sys_user.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN sys_user.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN sys_user.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN sys_user.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE sys_oper_log (
  id BIGINT NOT NULL,
  user_id BIGINT,
  username VARCHAR(64),
  module VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  method VARCHAR(16) NOT NULL,
  request_uri VARCHAR(512) NOT NULL,
  request_params VARCHAR(2000),
  result VARCHAR(7) NOT NULL,
  error_msg VARCHAR(1000),
  ip VARCHAR(45),
  cost_ms BIGINT NOT NULL,
  oper_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_sys_oper_log PRIMARY KEY (id),
  CONSTRAINT ck_sys_oper_log_id CHECK (id > 0),
  CONSTRAINT ck_sys_oper_log_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);
CREATE INDEX idx_sys_oper_log_time ON sys_oper_log (oper_time);
COMMENT ON TABLE sys_oper_log IS '操作审计日志，只追加表；省略修改字段与逻辑删除';
COMMENT ON COLUMN sys_oper_log.id IS '正数雪花主键';
COMMENT ON COLUMN sys_oper_log.user_id IS '操作人 ID；匿名请求为空';
COMMENT ON COLUMN sys_oper_log.username IS '操作人登录名；无法识别时为空';
COMMENT ON COLUMN sys_oper_log.module IS '操作模块';
COMMENT ON COLUMN sys_oper_log.action IS '操作名称';
COMMENT ON COLUMN sys_oper_log.method IS 'HTTP 方法';
COMMENT ON COLUMN sys_oper_log.request_uri IS '请求路径';
COMMENT ON COLUMN sys_oper_log.request_params IS '脱敏请求参数；没有参数时为空';
COMMENT ON COLUMN sys_oper_log.result IS '结果：SUCCESS 成功、FAILURE 失败';
COMMENT ON COLUMN sys_oper_log.error_msg IS '错误摘要；成功时为空';
COMMENT ON COLUMN sys_oper_log.ip IS '来源 IP；无法识别时为空';
COMMENT ON COLUMN sys_oper_log.cost_ms IS '请求耗时（毫秒）';
COMMENT ON COLUMN sys_oper_log.oper_time IS '操作时间（UTC）';
