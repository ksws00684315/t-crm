-- BD register links (task 12c, design §4.2, §5.2). One active code per BD; rotating sets
-- status to 0 and inserts a new row. The code is not a credential, so it is stored in plaintext.
-- No logical delete: a revoked code stays for audit, which is why is_deleted is omitted.

CREATE TABLE crm_kol_register_code (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  bd_user_id BIGINT NOT NULL,
  code VARCHAR(32) NOT NULL,
  status SMALLINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_kol_register_code PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_register_code_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_register_code_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_kol_register_code_bd CHECK (bd_user_id > 0),
  CONSTRAINT ck_crm_kol_register_code_code CHECK (code ~ '^[0-9A-Za-z]{32}$'),
  CONSTRAINT ck_crm_kol_register_code_status CHECK (status IN (0, 1))
);
-- The code is the public identifier of one company and one BD, so it is unique across the table.
CREATE UNIQUE INDEX uk_crm_kol_register_code_code ON crm_kol_register_code (code);
-- At most one active link per BD. A BD user belongs to one company, so tenant_id is not a prefix.
CREATE UNIQUE INDEX uk_crm_kol_register_code_active_bd
  ON crm_kol_register_code (bd_user_id) WHERE status = 1;
-- Admin reads of "my link" are filtered by the company and this BD.
CREATE INDEX idx_crm_kol_register_code_bd
  ON crm_kol_register_code (tenant_id, bd_user_id);
COMMENT ON TABLE crm_kol_register_code IS '达人注册链接；注册码明文保存（只决定公司与归属商务，不是登录凭证）；同一商务最多一条有效码，作废后换新码；不使用逻辑删除，作废只把 status 改为 0';
COMMENT ON COLUMN crm_kol_register_code.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol_register_code.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_kol_register_code.bd_user_id IS '归属商务 sys_user.id（可作为商务的子账号）';
COMMENT ON COLUMN crm_kol_register_code.code IS '32 位随机 Base62 注册码，全表唯一；出现在达人端 /register?c= 链接中';
COMMENT ON COLUMN crm_kol_register_code.status IS '状态：1 有效、0 已作废';
COMMENT ON COLUMN crm_kol_register_code.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_register_code.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_kol_register_code.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_register_code.update_time IS '修改时间（UTC）';
