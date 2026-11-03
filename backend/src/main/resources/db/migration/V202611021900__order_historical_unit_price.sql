ALTER TABLE crm_order_item ADD COLUMN historical_unit_price numeric(14, 2);
COMMENT ON COLUMN crm_order_item.historical_unit_price IS 'Historical unit sale price from the source order; NULL when unavailable; never inferred from paid amount or current product price';
ALTER TABLE crm_order_item ADD CONSTRAINT ck_order_item_historical_unit_price CHECK (historical_unit_price >= 0);
