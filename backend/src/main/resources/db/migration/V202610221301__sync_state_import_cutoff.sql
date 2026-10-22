-- Task 40 (row 42, 09.03): remember each shop's latest import data cutoff time.

ALTER TABLE crm_order_sync_state ADD COLUMN last_import_kind VARCHAR(8);
ALTER TABLE crm_order_sync_state ADD COLUMN last_import_cutoff_at TIMESTAMPTZ(3);
ALTER TABLE crm_order_sync_state ADD COLUMN last_import_task_id BIGINT;
ALTER TABLE crm_order_sync_state ADD CONSTRAINT ck_crm_order_sync_state_import_kind
  CHECK (last_import_kind IS NULL OR last_import_kind IN ('ORDER', 'REFUND'));

COMMENT ON COLUMN crm_order_sync_state.last_import_kind IS '最近一次导入类型：ORDER 订单 / REFUND 退款';
COMMENT ON COLUMN crm_order_sync_state.last_import_cutoff_at IS '最近一次导入文件的数据截至时间（dataCutoffTime，任务 40）';
COMMENT ON COLUMN crm_order_sync_state.last_import_task_id IS '最近一次导入任务 crm_task.id';
