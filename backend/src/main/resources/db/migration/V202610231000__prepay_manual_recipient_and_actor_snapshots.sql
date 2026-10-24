-- Free-form recipient information remains encrypted and belongs only to this application.
ALTER TABLE crm_prepay ALTER COLUMN payout_account_cipher TYPE TEXT;
ALTER TABLE crm_prepay DROP CONSTRAINT ck_crm_prepay_account_type;
ALTER TABLE crm_prepay ADD CONSTRAINT ck_crm_prepay_account_type
  CHECK (payout_account_type IS NULL OR payout_account_type IN ('PAYPAL', 'USDT', 'MANUAL'));
ALTER TABLE crm_prepay ADD CONSTRAINT ck_crm_prepay_manual_recipient
  CHECK (payout_account_type <> 'MANUAL' OR
    (payout_account_id IS NULL AND payout_account_cipher IS NOT NULL
      AND payout_currency IS NULL AND payout_network IS NULL AND payout_holder_name IS NULL));
COMMENT ON COLUMN crm_prepay.payout_account_type IS '本单收款快照类型：PAYPAL、USDT（兼容旧请求）或 MANUAL 手填';
COMMENT ON COLUMN crm_prepay.payout_account_cipher IS '本单完整收款信息密文；手填最多1000字符保留换行；详情同范围鉴权及读取审计';
COMMENT ON COLUMN crm_prepay.payout_account_id IS '旧账户快照来源；手填 MANUAL 为空，不写入达人账户档案';

ALTER TABLE crm_prepay_history ADD COLUMN operator_name VARCHAR(128);
ALTER TABLE crm_prepay_history ADD COLUMN operator_role VARCHAR(32);
ALTER TABLE crm_prepay_payment ADD COLUMN operator_name VARCHAR(128);
ALTER TABLE crm_prepay_payment ADD COLUMN operator_role VARCHAR(32);
COMMENT ON COLUMN crm_prepay_history.operator_name IS '办理时人员姓名快照；无法识别的历史人员为空';
COMMENT ON COLUMN crm_prepay_history.operator_role IS '本次办理身份：BUSINESS、OPERATIONS_REVIEW、FINANCE_REVIEW、PAYMENT、SYSTEM、HISTORICAL';
COMMENT ON COLUMN crm_prepay_payment.operator_name IS '付款登记人员姓名快照';
COMMENT ON COLUMN crm_prepay_payment.operator_role IS '付款登记办理身份 PAYMENT';

-- Best-effort names for legacy records; never replace an unresolved name with a numeric ID.
UPDATE crm_prepay_history h SET operator_name = u.nickname
FROM sys_user u WHERE u.id = h.operator_id;
UPDATE crm_prepay_payment p SET operator_name = u.nickname
FROM sys_user u WHERE u.id = p.operator_id;
UPDATE crm_prepay_history SET operator_role = CASE
  WHEN operator_id = 0 THEN 'SYSTEM'
  WHEN action IN ('SUBMITTED', 'DETAILS_COMPLETED', 'CANCELLED') THEN 'BUSINESS'
  WHEN action = 'OPERATIONS_APPROVED' THEN 'OPERATIONS_REVIEW'
  WHEN action IN ('FINANCE_APPROVED', 'APPROVED') THEN 'FINANCE_REVIEW'
  WHEN action = 'PAID' THEN 'PAYMENT'
  ELSE 'HISTORICAL' END;
UPDATE crm_prepay_payment SET operator_role = 'PAYMENT';
ALTER TABLE crm_prepay_history ADD CONSTRAINT ck_crm_prepay_history_role
  CHECK (operator_role IS NULL OR operator_role IN
    ('BUSINESS', 'OPERATIONS_REVIEW', 'FINANCE_REVIEW', 'PAYMENT', 'SYSTEM', 'HISTORICAL'));
ALTER TABLE crm_prepay_payment ADD CONSTRAINT ck_crm_prepay_payment_role
  CHECK (operator_role IS NULL OR operator_role = 'PAYMENT');
