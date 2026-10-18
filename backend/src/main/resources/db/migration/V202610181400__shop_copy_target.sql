-- Task 17b (ADR-019): default copy-target shop per company.

ALTER TABLE crm_shop ADD COLUMN copy_target BOOLEAN NOT NULL DEFAULT FALSE;

-- At most one default copy shop per company among live rows.
CREATE UNIQUE INDEX uk_crm_shop_copy_target
  ON crm_shop (tenant_id)
  WHERE copy_target = TRUE AND is_deleted = FALSE;

COMMENT ON COLUMN crm_shop.copy_target IS '默认复制店铺：令牌未指定目标店铺时复制到它；每家公司最多一个（ADR-019）';
