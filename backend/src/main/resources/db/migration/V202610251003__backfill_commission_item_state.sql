-- Task 62 / ADR-032 §2: register existing order item lines as unverified snapshots (design §5.2).
-- Rows keep source_version NULL: the settlement engine writes the first revision on the next sync.

INSERT INTO crm_commission_item_state (
  id, tenant_id, order_id, order_item_id, kol_id, source_key, source_version,
  settlement_state, after_sales, verified, accrued_commission_cny, released_commission_cny,
  release_basis, create_time, update_time)
SELECT
  62100000000 + (ROW_NUMBER() OVER (ORDER BY i.tenant_id, i.id))::BIGINT,
  i.tenant_id,
  i.order_id,
  i.id,
  CASE WHEN i.attribution_type IN (1, 2) THEN i.kol_id END,
  'ORDER_ITEM:' || i.id,
  NULL,
  'UNKNOWN',
  EXISTS (
    SELECT 1 FROM crm_refund r
    WHERE r.order_item_id = i.id AND r.is_deleted = FALSE AND r.refund_status <> 'CLOSED'
  ),
  FALSE,
  0,
  0,
  'UNVERIFIED',
  now(),
  now()
FROM crm_order_item i
WHERE i.is_deleted = FALSE;
