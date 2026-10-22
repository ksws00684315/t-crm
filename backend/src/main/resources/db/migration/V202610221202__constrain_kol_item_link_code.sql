-- Task 39: link codes are required, shaped, and unique per company.
-- This is the sixth index on crm_kol_item (the per-table limit), so the list
-- does not add another index for create_time or shop_id.
-- The default only covers inserts that forget to set the column. Application
-- inserts generate the code themselves. gen_random_uuid() is built into PostgreSQL.

ALTER TABLE crm_kol_item ALTER COLUMN link_code SET NOT NULL;

ALTER TABLE crm_kol_item
  ADD CONSTRAINT ck_crm_kol_item_link_code CHECK (link_code ~ '^KPL-[0-9A-F]{12}$');

CREATE UNIQUE INDEX uk_crm_kol_item_link ON crm_kol_item (tenant_id, link_code);

ALTER TABLE crm_kol_item
  ALTER COLUMN link_code SET DEFAULT (
    'KPL-' || upper(substr(md5(gen_random_uuid()::text), 1, 12))
  );
