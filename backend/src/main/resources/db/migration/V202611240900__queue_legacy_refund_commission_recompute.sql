-- Recompute verified lines whose legacy after-sales result was zero despite a positive accrual.
-- Use the existing per-order retry path so that revisions and withdrawal pauses stay consistent.
UPDATE crm_order o
   SET settlement_recompute_failures = 1
 WHERE o.settlement_recompute_failures = 0
   AND o.pay_time IS NOT NULL
   AND NOT o.is_deleted
   AND EXISTS (
       SELECT 1
         FROM crm_commission_item_state s
         JOIN crm_commission_item_revision r
           ON r.tenant_id = s.tenant_id AND r.item_state_id = s.id
          AND r.source_version = s.source_version
        WHERE s.tenant_id = o.tenant_id AND s.order_id = o.id
          AND NOT s.is_deleted AND s.verified AND s.after_sales
          AND s.release_basis = 'AFTER_SALES'
          AND s.accrued_commission_cny = 0 AND s.released_commission_cny = 0
          AND ROUND(GREATEST(s.basis_cny - CASE WHEN s.basis_includes_refund THEN 0
                         ELSE COALESCE(s.successful_refund_cny, 0) END, 0) * r.rate, 2) > 0
   );
