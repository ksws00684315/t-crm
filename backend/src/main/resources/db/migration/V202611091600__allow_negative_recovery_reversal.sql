-- 任务 141（L-118）：佣金扣回时按 LIFO 追加负数冲抵记录撤回已有冲抵，
-- 因此 crm_commission_recovery.amount_cny 需要允许负数（0 仍然拒绝）。
ALTER TABLE crm_commission_recovery DROP CONSTRAINT ck_crm_commission_recovery_amount;
ALTER TABLE crm_commission_recovery
  ADD CONSTRAINT ck_crm_commission_recovery_amount CHECK (amount_cny <> 0);

COMMENT ON TABLE crm_commission_recovery IS
  '可结算佣金正向入账按时间及 ID FIFO 分配给预付负向台账的持久化冲抵记录；负数行是佣金扣回（COMMISSION_ADJUSTMENT）时按 LIFO 追加的撤回记录';
COMMENT ON COLUMN crm_commission_recovery.amount_cny IS
  '本次冲抵金额 CNY：正数为入账冲抵，负数为扣回撤回，不存 0';
COMMENT ON COLUMN crm_commission_recovery.credit_ledger_id IS
  '冲抵来源台账 ID：正数行指向 COMMISSION_CREDIT 正向佣金台账，负数行指向那笔 COMMISSION_ADJUSTMENT 扣回台账';
