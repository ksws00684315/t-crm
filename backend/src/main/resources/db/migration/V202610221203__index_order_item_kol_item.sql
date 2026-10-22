-- Task 39: one grouped sales query for the creator items on the current page.
-- The partial index covers attributed lines. The query joins crm_order by primary key
-- and keeps PAID / SHIPPED / RECEIVED / COMPLETED. crm_order_item stays within 6 indexes
-- (unique identity, kol_order, attribution type, and this one, plus the primary key).

CREATE INDEX idx_crm_order_item_kol_item
  ON crm_order_item (kol_item_id)
  WHERE kol_item_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON INDEX idx_crm_order_item_kol_item IS '达人商品页按当前页 kol_item_id 汇总销量与支付金额';
