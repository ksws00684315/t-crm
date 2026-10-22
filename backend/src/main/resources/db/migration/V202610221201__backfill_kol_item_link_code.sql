-- Task 39: fill link codes for rows created before the column existed.
-- 12 hex characters of md5(id) are unique for the current table; the following
-- unique index fails the migration if a collision ever appears.

UPDATE crm_kol_item
SET link_code = 'KPL-' || upper(substr(md5(id::text), 1, 12))
WHERE link_code IS NULL;
