-- Task 63: withdrawal application, review and payment notify through the in-app message outbox,
-- so crm_notification has to accept the four WITHDRAWAL_* types and keep one message per user and
-- event (same idempotent shape as the prepay event index).

ALTER TABLE crm_notification DROP CONSTRAINT ck_crm_notification_type;
ALTER TABLE crm_notification ADD CONSTRAINT ck_crm_notification_type CHECK (
  type IN ('PAYOUT_ACCOUNT_SUBMITTED', 'ORDER_SYNC_FAILED', 'BD_TRANSFER_CANCELLED', 'KOLS_UNASSIGNED',
           'PREPAY_SUBMITTED', 'PREPAY_APPROVED', 'PREPAY_REJECTED', 'PREPAY_PAID',
           'DEPARTURE_PLAN_SUBMITTED', 'DEPARTURE_PLAN_EXECUTED',
           'WITHDRAWAL_SUBMITTED', 'WITHDRAWAL_APPROVED', 'WITHDRAWAL_REJECTED', 'WITHDRAWAL_PAID'));
COMMENT ON COLUMN crm_notification.type IS
  '消息类型：收款账户提交、同步失败、转移取消、未分配、预付提交/通过/驳回/支付、离职提交/执行、提现提交/通过/驳回/支付';

CREATE UNIQUE INDEX uk_crm_notification_withdrawal_event
  ON crm_notification (tenant_id, user_id, biz_type, biz_id, type)
  WHERE biz_type = 'WITHDRAWAL';
