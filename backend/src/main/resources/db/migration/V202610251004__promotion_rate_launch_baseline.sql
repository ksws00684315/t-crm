-- Task 62 / ADR-032 §3: launch-baseline CONFIG records so KOLs that already have a default rate
-- get versioned rates for orders paid after the commission launch. Orders paid before the launch
-- without a versioned rate stay unverified (manual verification, D9: later rates never backdate).
--
-- Reserved primary-key segments for this script (far below snowflake ids; do not reuse in later
-- migrations): baseline promotion records use 62200000000 + row_number().

INSERT INTO crm_kol_promotion_record (
  id, tenant_id, kol_id, change_type, before_json, after_json, remark, version_no, effective_time,
  create_time)
SELECT
  62200000000 + (ROW_NUMBER() OVER (ORDER BY k.tenant_id, k.id))::BIGINT,
  k.tenant_id,
  k.id,
  'CONFIG',
  '{}'::jsonb,
  jsonb_build_object('defaultRate', btrim(to_char(k.default_rate, '990.9999')), 'configVersion', 1),
  '佣金上线基线（任务 62）：按达人已有默认比例登记首个配置版本',
  1,
  now(),
  now()
FROM crm_kol k
WHERE k.is_deleted = FALSE
  AND k.default_rate IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM crm_kol_promotion_record r
    WHERE r.kol_id = k.id AND r.change_type = 'CONFIG'
  );

-- Bump the config version only for KOLs that received a baseline record in this very script
-- (identified by the reserved id segment). Existing CONFIG records carry snowflake ids far above
-- the segment, so KOLs with real history keep their current config_version untouched.
UPDATE crm_kol k
SET config_version = 1, update_time = now()
WHERE k.is_deleted = FALSE
  AND k.id IN (
    SELECT r.kol_id FROM crm_kol_promotion_record r
    WHERE r.change_type = 'CONFIG' AND r.id >= 62200000000 AND r.id < 62300000000
  );
