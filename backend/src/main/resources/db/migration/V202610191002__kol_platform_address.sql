-- Task 29 / ADR-025: per-platform address replaces single landing_url.

ALTER TABLE crm_kol_platform
  ADD COLUMN address VARCHAR(512);

-- Copy legacy landing_url onto every platform row of the same KOL.
UPDATE crm_kol_platform p
SET address = k.landing_url
FROM crm_kol k
WHERE p.kol_id = k.id
  AND k.landing_url IS NOT NULL
  AND k.landing_url <> '';

ALTER TABLE crm_kol_platform
  ADD CONSTRAINT ck_crm_kol_platform_address
    CHECK (address IS NULL OR address ~ '^https://');

COMMENT ON COLUMN crm_kol_platform.address IS
  '该代购平台上的达人专属地址，https，≤512；取代原 crm_kol.landing_url（ADR-025）';
