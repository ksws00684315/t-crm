-- Task 105 (L-056): partial index for OrderMapper.listSettlementRecomputePending —
-- shop settlement recompute only scans orders whose failure mark is > 0.
-- Columns match the query predicates (tenant + shop) and ORDER BY id; is_deleted stays in the
-- predicate so soft-deleted rows never enter the index.

CREATE INDEX idx_crm_order_settlement_recompute_pending
    ON crm_order (tenant_id, shop_id, id)
    WHERE settlement_recompute_failures > 0 AND is_deleted = FALSE;

COMMENT ON INDEX idx_crm_order_settlement_recompute_pending IS
    '店铺结算计佣待重算订单扫描（任务 105 L-056）：仅覆盖 settlement_recompute_failures > 0 且未删除的行，支撑 listSettlementRecomputePending 按 shop_id / id 取数';
