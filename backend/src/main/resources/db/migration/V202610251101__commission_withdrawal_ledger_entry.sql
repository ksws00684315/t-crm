-- Task 63: a paid withdrawal leaves the wallet through a negative COMMISSION_WITHDRAWAL entry in
-- the one ledger of V202610221900. Nothing else changes; the wallet stays the only balance source.

ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_type;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_type CHECK (
  entry_type IN ('COMMISSION_CREDIT', 'COMMISSION_WITHDRAWAL', 'PREPAY_DEDUCT'));
ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_amount;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_amount CHECK (
  (entry_type = 'COMMISSION_CREDIT' AND amount_cny > 0)
  OR (entry_type = 'COMMISSION_WITHDRAWAL' AND amount_cny < 0)
  OR (entry_type = 'PREPAY_DEDUCT' AND amount_cny < 0));

COMMENT ON COLUMN crm_commission_ledger.entry_type IS
  '类型：COMMISSION_CREDIT 佣金入账、COMMISSION_WITHDRAWAL 提现支付扣减、PREPAY_DEDUCT 预付抵扣';
