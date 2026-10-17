-- 订单明细唯一键只约束未删除行：导入明细被接口同步逻辑删除后，同键新明细可再插入。
ALTER TABLE crm_order_item DROP CONSTRAINT uk_crm_order_item_identity;

CREATE UNIQUE INDEX uk_crm_order_item_identity
  ON crm_order_item (order_id, sub_order_id)
  WHERE is_deleted = FALSE;

COMMENT ON INDEX uk_crm_order_item_identity IS
  '有效明细在同一订单内子订单号唯一；已逻辑删除的行不占键，便于导入被接口覆盖后再次写入同键';
