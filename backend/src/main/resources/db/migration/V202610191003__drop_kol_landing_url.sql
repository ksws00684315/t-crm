-- Task 29 / ADR-025: drop landing_url after copying onto platform rows.

ALTER TABLE crm_kol DROP CONSTRAINT IF EXISTS ck_crm_kol_landing_url;
ALTER TABLE crm_kol DROP COLUMN landing_url;
