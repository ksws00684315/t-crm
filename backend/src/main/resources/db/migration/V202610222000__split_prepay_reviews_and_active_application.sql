-- Split incomplete applications and the two approval stages without reopening completed work.
ALTER TABLE crm_prepay DROP CONSTRAINT ck_crm_prepay_status;
ALTER TABLE crm_prepay ALTER COLUMN status TYPE VARCHAR(32);
UPDATE crm_prepay
   SET status = CASE
     WHEN reason IS NOT NULL AND expected_pay_date IS NOT NULL
       AND payout_account_id IS NOT NULL AND deduct_from_commission IS NOT NULL
       THEN 'PENDING_OPERATIONS_REVIEW'
     ELSE 'WAITING_DETAILS'
   END
 WHERE status = 'PENDING_APPROVAL';
ALTER TABLE crm_prepay ADD CONSTRAINT ck_crm_prepay_status
  CHECK (status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW', 'PENDING_FINANCE_REVIEW', 'APPROVED', 'PAID', 'REJECTED', 'CANCELLED'));
COMMENT ON COLUMN crm_prepay.status IS '状态：WAITING_DETAILS 待商务完善、PENDING_OPERATIONS_REVIEW 运营待审核、PENDING_FINANCE_REVIEW 财务待审核、APPROVED 待支付、PAID 已支付、REJECTED 已驳回、CANCELLED 已撤销';

ALTER TABLE crm_prepay_history DROP CONSTRAINT ck_crm_prepay_history_action;
ALTER TABLE crm_prepay_history ADD CONSTRAINT ck_crm_prepay_history_action
  CHECK (action IN ('SUBMITTED', 'DETAILS_COMPLETED', 'OPERATIONS_APPROVED', 'FINANCE_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED', 'PAID'));
COMMENT ON COLUMN crm_prepay_history.action IS '动作：提交、补录完成、运营审核通过、财务审核通过、驳回、撤销、付款完成';

-- Keep existing finance staff able to review while separating review from payment at runtime.
LOCK TABLE sys_user_permission IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO sys_user_permission (id, tenant_id, user_id, permission_code, create_by, create_time)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_user_permission)
         + row_number() OVER (ORDER BY p.tenant_id, p.user_id),
       p.tenant_id, p.user_id, 'prepay:finance-review', NULL, CURRENT_TIMESTAMP(3)
  FROM sys_user_permission p
 WHERE p.permission_code = 'prepay:pay'
   AND NOT EXISTS (
     SELECT 1 FROM sys_user_permission existing
      WHERE existing.tenant_id = p.tenant_id
        AND existing.user_id = p.user_id
        AND existing.permission_code = 'prepay:finance-review'
   );

CREATE UNIQUE INDEX uk_crm_prepay_one_active_per_kol
  ON crm_prepay (tenant_id, kol_id)
  WHERE status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW', 'PENDING_FINANCE_REVIEW', 'APPROVED');
COMMENT ON INDEX uk_crm_prepay_one_active_per_kol IS '每家公司每位达人同时最多一张进行中的预付申请';

