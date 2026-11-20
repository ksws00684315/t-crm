-- Task 144 review: the incremental rollup scan (OrderDailyStatsMapper.changedDays) runs every
-- five minutes per tenant and filters crm_order / crm_order_item / crm_refund by
-- (tenant_id, update_time); no existing index supports that predicate, so the scan degrades to
-- a filtered sweep as the tables grow. The scan must also see soft-deleted rows, therefore the
-- indexes are not partial.

CREATE INDEX idx_crm_order_tenant_update
    ON crm_order (tenant_id, update_time);

CREATE INDEX idx_crm_order_item_tenant_update
    ON crm_order_item (tenant_id, update_time);

CREATE INDEX idx_crm_refund_tenant_update
    ON crm_refund (tenant_id, update_time);

COMMENT ON INDEX idx_crm_order_tenant_update IS
    '经营分析增量汇总扫描（任务 144）：支撑 changedDays 按 tenant_id + update_time 找出有变动的订单';
COMMENT ON INDEX idx_crm_order_item_tenant_update IS
    '经营分析增量汇总扫描（任务 144）：支撑 changedDays 按 tenant_id + update_time 找出有变动的订单明细';
COMMENT ON INDEX idx_crm_refund_tenant_update IS
    '经营分析增量汇总扫描（任务 144）：支撑 changedDays 按 tenant_id + update_time 找出有变动的退款';
