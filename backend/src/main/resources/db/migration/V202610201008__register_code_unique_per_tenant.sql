DROP INDEX uk_crm_kol_register_code_active_bd;

CREATE UNIQUE INDEX uk_crm_kol_register_code_active_tenant_bd
  ON crm_kol_register_code (tenant_id, bd_user_id) WHERE status = 1;

COMMENT ON TABLE crm_kol_register_code IS
  '达人注册链接；注册码明文保存（只决定公司与归属账号，不是登录凭证）；每家公司内同一账号最多一条有效码，作废后可换新码；不使用逻辑删除，作废只把 status 改为 0';
COMMENT ON COLUMN crm_kol_register_code.bd_user_id IS
  '邀请码所属账号 sys_user.id；商务码用于绑定商务，超管码注册时不绑定商务';
COMMENT ON INDEX uk_crm_kol_register_code_active_tenant_bd IS
  '每家公司内每个邀请码所属账号最多一条有效码';
