-- Task 63 / ADR-032 §12: withdrawal:pay is a new FUNDS permission (FINANCE post template carries
-- it for new accounts). Existing staff who already register prepay payments are the finance
-- payers, so they receive the code here -- the same copy the prepay split used for
-- prepay:finance-review in V202610222000; template changes never backfill existing members.

LOCK TABLE sys_user_permission IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO sys_user_permission (id, tenant_id, user_id, permission_code, create_by, create_time)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_user_permission)
         + row_number() OVER (ORDER BY p.tenant_id, p.user_id),
       p.tenant_id, p.user_id, 'withdrawal:pay', NULL, CURRENT_TIMESTAMP(3)
  FROM sys_user_permission p
 WHERE p.permission_code = 'prepay:pay'
   AND NOT EXISTS (
     SELECT 1 FROM sys_user_permission existing
      WHERE existing.tenant_id = p.tenant_id
        AND existing.user_id = p.user_id
        AND existing.permission_code = 'withdrawal:pay'
   );
