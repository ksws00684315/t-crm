ALTER TABLE crm_kol_bd_transfer ADD COLUMN reason VARCHAR(255);
COMMENT ON COLUMN crm_kol_bd_transfer.reason IS '发起转移时的原因（最多 255 字）；可为空';
