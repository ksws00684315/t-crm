-- Customer 10-20 supplement: invite code, per-platform address and remark are optional.
-- Reclassify historical rows to match the current service-side completeness rule.
UPDATE crm_kol k
SET relation_status = CASE
  WHEN k.review_status = 'APPROVED'
   AND k.default_rate IS NOT NULL
   AND k.sheet_url IS NOT NULL AND btrim(k.sheet_url) <> ''
   AND EXISTS (
     SELECT 1 FROM crm_kol_platform p
     WHERE p.kol_id = k.id AND p.is_deleted = FALSE
   )
  THEN 'PROMOTING'
  ELSE 'PENDING_PROMOTION'
END;
