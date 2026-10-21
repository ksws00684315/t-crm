ALTER TABLE sys_oper_log ALTER COLUMN request_params TYPE TEXT;

COMMENT ON COLUMN sys_oper_log.request_params IS '脱敏请求参数；没有参数时为空';
