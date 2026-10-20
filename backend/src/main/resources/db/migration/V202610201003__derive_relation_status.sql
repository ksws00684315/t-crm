-- Task 33: relation status is derived from review/configuration, with frozen/no-BD display overrides.
ALTER TABLE crm_kol DROP CONSTRAINT ck_crm_kol_relation_status;

UPDATE crm_kol k
SET relation_status = CASE
  WHEN k.review_status = 'APPROVED'
   AND k.default_rate IS NOT NULL
   AND k.invite_code IS NOT NULL AND btrim(k.invite_code) <> ''
   AND k.sheet_url IS NOT NULL AND btrim(k.sheet_url) <> ''
   AND EXISTS (
     SELECT 1 FROM crm_kol_platform p
     WHERE p.kol_id = k.id AND p.is_deleted = FALSE
       AND p.address IS NOT NULL AND btrim(p.address) <> ''
   )
  THEN 'PROMOTING'
  ELSE 'PENDING_PROMOTION'
END;

ALTER TABLE crm_kol
  ADD CONSTRAINT ck_crm_kol_relation_status
    CHECK (relation_status IN ('PENDING_PROMOTION', 'PROMOTING'));

COMMENT ON COLUMN crm_kol.relation_status IS
  '存储关系状态：PENDING_PROMOTION 待推广 / PROMOTING 推广中；由审核与推广配置自动决定；冻结及未绑定商务状态仅在展示时覆盖';
