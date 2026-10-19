-- Task 29 / ADR-025: drop superseded promotion_status after mapping.

ALTER TABLE crm_kol DROP CONSTRAINT IF EXISTS ck_crm_kol_promotion_status;
ALTER TABLE crm_kol DROP COLUMN promotion_status;
