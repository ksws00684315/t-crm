-- Task 33: keep the required promotion note separate from the general KOL profile remark.
ALTER TABLE crm_kol
  ADD COLUMN promotion_remark VARCHAR(500);

UPDATE crm_kol k
SET promotion_remark = (
  SELECT r.remark
  FROM crm_kol_promotion_record r
  WHERE r.kol_id = k.id
    AND r.change_type = 'CONFIG'
  ORDER BY r.version_no DESC NULLS LAST, r.create_time DESC, r.id DESC
  LIMIT 1
);

COMMENT ON COLUMN crm_kol.promotion_remark IS
  '推广配置备注，保存完整推广配置时必填；与达人资料备注分开存储';
