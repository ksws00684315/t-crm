-- Task 62 / ADR-032 §1: ledger entry types extend to four; release source keys need more room.

ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_type;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_type CHECK (
  entry_type IN ('COMMISSION_CREDIT', 'COMMISSION_ADJUSTMENT', 'PREPAY_DEDUCT', 'COMMISSION_WITHDRAWAL')
);
ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_amount;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_amount CHECK (
  (entry_type = 'COMMISSION_CREDIT' AND amount_cny > 0)
  OR (entry_type IN ('COMMISSION_ADJUSTMENT', 'PREPAY_DEDUCT', 'COMMISSION_WITHDRAWAL') AND amount_cny < 0)
);
ALTER TABLE crm_commission_ledger ALTER COLUMN source_key TYPE VARCHAR(160);

COMMENT ON TABLE crm_commission_ledger IS '不可变佣金钱包台账（任务 47A 建表，任务 62 扩展类型）；正向入账参与预付 FIFO 冲抵；余额汇总可由事件核对';
COMMENT ON COLUMN crm_commission_ledger.entry_type IS '账目类型：COMMISSION_CREDIT 佣金释放与正向差额、COMMISSION_ADJUSTMENT 释放后退款与归属修正负向差额、PREPAY_DEDUCT 预付抵扣、COMMISSION_WITHDRAWAL 提现付款核销';
COMMENT ON COLUMN crm_commission_ledger.source_key IS '公司内记账来源幂等键；佣金行为 COMMISSION_ITEM:{item_state_id}:{source_version}（归属回冲追加 :K{原达人ID}），预付 PREPAY_DEDUCT:{prepay_id}';
