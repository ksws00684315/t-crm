-- Task 33: only configurations with all required values, including remark, remain PROMOTING.
UPDATE crm_kol k
SET relation_status = CASE
  WHEN k.review_status = 'APPROVED'
   AND k.default_rate IS NOT NULL
   AND k.invite_code IS NOT NULL AND btrim(k.invite_code) <> ''
   AND k.sheet_url IS NOT NULL AND btrim(k.sheet_url) <> ''
   AND k.promotion_remark IS NOT NULL AND btrim(k.promotion_remark) <> ''
   AND EXISTS (
     SELECT 1 FROM crm_kol_platform p
     WHERE p.kol_id = k.id AND p.is_deleted = FALSE
       AND p.address IS NOT NULL AND btrim(p.address) <> ''
   )
  THEN 'PROMOTING'
  ELSE 'PENDING_PROMOTION'
END;
