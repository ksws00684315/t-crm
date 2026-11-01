-- Task 91 (L-023): per-order retry mark for the settlement commission recompute, so one failing
-- order no longer holds back the shop's pull checkpoint. crm_order already carries tenant_id.

ALTER TABLE crm_order
    ADD COLUMN settlement_recompute_failures INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_crm_order_settlement_recompute_failures CHECK (settlement_recompute_failures >= 0);

COMMENT ON COLUMN crm_order.settlement_recompute_failures IS '结算计佣重算连续失败次数（任务 91，L-023）：0=无待重算标记；大于 0=最近一次结算重算失败，检查点照常前移，下一轮只重试带标记的订单；重算成功清零；仅用于重试与排查，不参与计佣';
