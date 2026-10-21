-- Task 40 (row 42, 09.03): import tasks remember submitter parameters (dataCutoffTime).

ALTER TABLE crm_task ADD COLUMN params JSONB;

COMMENT ON COLUMN crm_task.params IS '提交参数（JSONB，任务 40 起）：订单/退款导入的 dataCutoffTime 等';
