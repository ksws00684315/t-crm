-- Task 05: allow PRODUCT_IMPORT and MAPPING_IMPORT on crm_task.task_type.

ALTER TABLE crm_task DROP CONSTRAINT ck_crm_task_type;
ALTER TABLE crm_task ADD CONSTRAINT ck_crm_task_type
  CHECK (task_type IN ('KOL_IMPORT', 'PRODUCT_IMPORT', 'MAPPING_IMPORT'));
COMMENT ON COLUMN crm_task.task_type IS '任务类型：KOL_IMPORT 达人导入、PRODUCT_IMPORT 原商品导入、MAPPING_IMPORT 对应关系导入（新增类型时通过迁移扩充约束）';
