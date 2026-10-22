-- Task 41: a departure plan is announced twice (submitted by the BD, executed by the job), so the
-- message type check of task 20 has to accept the two new types.

ALTER TABLE crm_notification DROP CONSTRAINT IF EXISTS ck_crm_notification_type;
ALTER TABLE crm_notification ADD CONSTRAINT ck_crm_notification_type CHECK (
    type IN (
        'PAYOUT_ACCOUNT_SUBMITTED',
        'ORDER_SYNC_FAILED',
        'BD_TRANSFER_CANCELLED',
        'KOLS_UNASSIGNED',
        'DEPARTURE_PLAN_SUBMITTED',
        'DEPARTURE_PLAN_EXECUTED'));

COMMENT ON COLUMN crm_notification.type IS '消息类型：收款账户提交、订单同步失败、预约转移取消、达人转为未分配、离职计划提交、离职计划执行';
