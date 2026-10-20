-- Task 33: bd_user_id identifies the owner of the invitation code, including a company super admin.
COMMENT ON COLUMN crm_kol_register_code.bd_user_id IS
  '邀请码所属账号（公司超管或可作为商务的子账号）；超管邀请码注册的达人不绑定商务';
