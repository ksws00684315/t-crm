-- Keep both workflows available on databases that already applied the original prepay preview.
ALTER TABLE crm_notification DROP CONSTRAINT ck_crm_notification_type;
ALTER TABLE crm_notification ADD CONSTRAINT ck_crm_notification_type CHECK (
  type IN ('PAYOUT_ACCOUNT_SUBMITTED', 'ORDER_SYNC_FAILED', 'BD_TRANSFER_CANCELLED', 'KOLS_UNASSIGNED',
           'PREPAY_SUBMITTED', 'PREPAY_APPROVED', 'PREPAY_REJECTED', 'PREPAY_PAID',
           'DEPARTURE_PLAN_SUBMITTED', 'DEPARTURE_PLAN_EXECUTED')
);
COMMENT ON COLUMN crm_notification.type IS '消息类型：收款账户提交、同步失败、转移取消、未分配、预付提交/通过/驳回/支付、离职提交/执行';
