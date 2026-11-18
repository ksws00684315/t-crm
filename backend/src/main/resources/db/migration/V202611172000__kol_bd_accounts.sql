-- Task 156: record which company business account manages each creator (customer request 2026-11-16).
ALTER TABLE crm_kol
  ADD COLUMN bd_discord VARCHAR(64),
  ADD COLUMN bd_whatsapp VARCHAR(64);

COMMENT ON COLUMN crm_kol.bd_discord IS
  '公司商务的 Discord 账号，记录该达人由哪个商务账号管理；空表示未填写';
COMMENT ON COLUMN crm_kol.bd_whatsapp IS
  '公司商务的 WhatsApp 账号，记录该达人由哪个商务账号管理；空表示未填写';
