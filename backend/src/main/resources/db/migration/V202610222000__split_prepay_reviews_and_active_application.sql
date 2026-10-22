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

-- Legacy rows could contain multiple active applications for one creator. Keep the oldest task
-- active and explicitly close later duplicates before enforcing the new one-active-task rule.
LOCK TABLE crm_prepay, crm_prepay_history IN SHARE ROW EXCLUSIVE MODE;
WITH ranked AS (
  SELECT id,
         row_number() OVER (
           PARTITION BY tenant_id, kol_id
           ORDER BY CASE status
             WHEN 'APPROVED' THEN 0
             WHEN 'PENDING_FINANCE_REVIEW' THEN 1
             WHEN 'PENDING_OPERATIONS_REVIEW' THEN 2
             ELSE 3
           END, create_time, id
         ) AS position
    FROM crm_prepay
   WHERE status IN ('WAITING_DETAILS', 'PENDING_OPERATIONS_REVIEW', 'PENDING_FINANCE_REVIEW', 'APPROVED')
), closed AS (
  UPDATE crm_prepay p
     SET status = 'REJECTED',
         approval_reason = '系统迁移关闭重复申请：仅保留该达人最早创建的进行中申请。',
         update_by = NULL,
         update_time = CURRENT_TIMESTAMP(3)
    FROM ranked r
   WHERE p.id = r.id AND r.position > 1
  RETURNING p.id, p.tenant_id
)
INSERT INTO crm_prepay_history (id, tenant_id, prepay_id, action, operator_id, reason, create_time)
SELECT (SELECT COALESCE(MAX(id), 0) FROM crm_prepay_history)
         + row_number() OVER (ORDER BY closed.id),
       closed.tenant_id,
       closed.id,
       'REJECTED',
       0,
       '系统迁移关闭重复申请；保留同一达人最早创建的进行中申请继续处理。',
       CURRENT_TIMESTAMP(3)
  FROM closed;

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

