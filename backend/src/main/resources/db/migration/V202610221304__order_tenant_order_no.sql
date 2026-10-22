-- Task 40 (row 43, 09.04): order list filter by Weidian order number without a shop.

CREATE INDEX idx_crm_order_tenant_order_no ON crm_order (tenant_id, weidian_order_id);

COMMENT ON INDEX idx_crm_order_tenant_order_no IS '订单查询按订单号精确筛选（任务 40）';
