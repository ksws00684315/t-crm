-- Task 81 PR review (2026-10-30, M2): lines of unpaid orders get settlement state UNPAID, so the
-- unverified counts no longer join crm_order. Existing unpaid lines are backfilled as well.

ALTER TABLE crm_commission_item_state DROP CONSTRAINT ck_crm_commission_item_state_settlement;
ALTER TABLE crm_commission_item_state
  ADD CONSTRAINT ck_crm_commission_item_state_settlement
  CHECK (settlement_state IN ('CONFIRMED', 'UNSETTLED', 'UNKNOWN', 'UNPAID'));
COMMENT ON COLUMN crm_commission_item_state.settlement_state IS '微店结算状态：CONFIRMED 已结算、UNSETTLED 未结算、UNKNOWN 未知（字段待 M0）、UNPAID 订单未付款（不计入待核实）';

UPDATE crm_commission_item_state AS s
SET settlement_state = 'UNPAID'
FROM crm_order AS o
WHERE o.id = s.order_id AND o.pay_time IS NULL AND s.settlement_state <> 'UNPAID';
