-- Task 70 (B01): order list filters by the platform order time (crm_order.order_time), mirroring
-- the payment-time index the payFrom/payTo filters already use.

CREATE INDEX idx_crm_order_tenant_order_time ON crm_order (tenant_id, order_time);

COMMENT ON INDEX idx_crm_order_tenant_order_time IS '订单查询按平台下单时间筛选（任务 70 B01）';
