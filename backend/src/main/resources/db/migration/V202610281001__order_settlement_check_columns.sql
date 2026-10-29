-- Task 76: commission basis check reason on the order, and per-shop settlement pull checkpoints.

ALTER TABLE crm_order ADD COLUMN commission_basis_reason VARCHAR(32);
COMMENT ON COLUMN crm_order.commission_basis_reason IS '计佣基数核实结果（任务 76）：空=已给出基数或尚未检查；NO_SETTLEMENT 无已匹配结算流水 / UNKNOWN_FLOW_TYPE 流水类型未知 / INCOME_MISMATCH 货款收入与订单实付不符（含单位错误）/ MULTIPLE_KOLS 一单多个达人 / ALLOCATION_FAILED 无法按行实付分摊';

ALTER TABLE crm_order_sync_state ADD COLUMN settlement_pull_at TIMESTAMPTZ(3);
COMMENT ON COLUMN crm_order_sync_state.settlement_pull_at IS '结算明细最近一次增量拉取完成时间 UTC（任务 76）；按系统配置 weidian.settlement.sync-interval-minutes 判断到期';

ALTER TABLE crm_order_sync_state ADD COLUMN settlement_backfill_at TIMESTAMPTZ(3);
COMMENT ON COLUMN crm_order_sync_state.settlement_backfill_at IS '结算明细最近一次每日回扫完成时间 UTC（任务 76）；按北京时间日期与 weidian.settlement.backfill-hour 判断到期';
