CREATE TABLE sys_user (
  id BIGINT NOT NULL PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  email VARCHAR(254),
  role VARCHAR(16) NOT NULL,
  data_scope VARCHAR(3) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  last_login_time DATETIME(3),
  create_by BIGINT,
  create_time DATETIME(3) NOT NULL,
  update_by BIGINT,
  update_time DATETIME(3) NOT NULL,
  del_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sys_user_username (username),
  CONSTRAINT chk_sys_user_role CHECK (role IN ('SUPER_ADMIN', 'OPERATOR', 'FINANCE')),
  CONSTRAINT chk_sys_user_scope CHECK (data_scope IN ('ALL', 'OWN')),
  CONSTRAINT chk_sys_user_status CHECK (status IN (0, 1)),
  CONSTRAINT chk_sys_user_own CHECK (data_scope = 'ALL' OR role = 'OPERATOR')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_oper_log (
  id BIGINT NOT NULL PRIMARY KEY,
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
  oper_time DATETIME(3) NOT NULL,
  KEY idx_sys_oper_log_time (oper_time),
  CONSTRAINT chk_oper_log_result CHECK (result IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
