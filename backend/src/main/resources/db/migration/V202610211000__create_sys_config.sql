CREATE TABLE sys_config (
  id BIGINT NOT NULL CHECK (id > 0),
  config_key VARCHAR(128) NOT NULL,
  config_value VARCHAR(2048) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_sys_config PRIMARY KEY (id),
  CONSTRAINT uk_sys_config_config_key UNIQUE (config_key)
);

COMMENT ON TABLE sys_config IS '平台级系统配置覆盖项；没有记录时使用环境变量或代码默认值';
COMMENT ON COLUMN sys_config.id IS '雪花算法主键';
COMMENT ON COLUMN sys_config.config_key IS '系统配置唯一键';
COMMENT ON COLUMN sys_config.config_value IS '配置覆盖值；敏感密钥不存储在此表';
COMMENT ON COLUMN sys_config.update_by IS '最后修改的平台管理员用户 ID';
COMMENT ON COLUMN sys_config.update_time IS '最后修改时间（UTC）';
