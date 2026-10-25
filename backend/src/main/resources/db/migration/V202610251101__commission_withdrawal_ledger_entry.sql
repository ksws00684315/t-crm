-- Task 63 / ADR-032 §1: the withdrawal payment posts a negative COMMISSION_WITHDRAWAL entry
-- into the one ledger of V202610221900. The constraint text below is the exact union task 62
-- writes in V202610251002, so either migration may merge first and the end state is identical;
-- running after task 62 simply re-creates the same four-type rule.

ALTER TABLE crm_commission_ledger DROP CONSTRAINT IF EXISTS ck_crm_commission_ledger_type;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_type CHECK (
  entry_type IN ('COMMISSION_CREDIT', 'COMMISSION_ADJUSTMENT', 'PREPAY_DEDUCT',
                 'COMMISSION_WITHDRAWAL')
);
ALTER TABLE crm_commission_ledger DROP CONSTRAINT IF EXISTS ck_crm_commission_ledger_amount;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_amount CHECK (
  (entry_type = 'COMMISSION_CREDIT' AND amount_cny > 0)
  OR (entry_type IN ('COMMISSION_ADJUSTMENT', 'PREPAY_DEDUCT', 'COMMISSION_WITHDRAWAL')
        AND amount_cny < 0)
);

COMMENT ON COLUMN crm_commission_ledger.entry_type IS
  '账目类型：COMMISSION_CREDIT 佣金释放与正向差额、COMMISSION_ADJUSTMENT 释放后退款与归属修正负向差额、PREPAY_DEDUCT 预付抵扣、COMMISSION_WITHDRAWAL 提现付款核销';
