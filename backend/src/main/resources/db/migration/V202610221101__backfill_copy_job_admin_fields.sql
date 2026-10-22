-- Task 38: backfill rows created before the admin columns existed.

UPDATE crm_copy_job AS job
SET target_channel = token.target_channel
FROM crm_kol_copy_token AS token
WHERE job.token_id = token.id
  AND job.target_channel IS NULL
  AND token.target_channel IS NOT NULL;

UPDATE crm_copy_job
SET target_channel = 'WEIDIAN'
WHERE target_channel IS NULL;

ALTER TABLE crm_copy_job ALTER COLUMN target_channel SET NOT NULL;

UPDATE crm_copy_job
SET finish_time = update_time
WHERE status IN ('completed', 'failed', 'partial_failed')
  AND finish_time IS NULL;

-- One live original product for this item id in the same company. Several shops, or none, stay empty.
UPDATE crm_copy_job_item AS item
SET source_shop_id = product.shop_id
FROM (
  SELECT tenant_id, weidian_item_id, MIN(shop_id) AS shop_id
  FROM crm_product
  WHERE is_deleted = FALSE
  GROUP BY tenant_id, weidian_item_id
  HAVING COUNT(DISTINCT shop_id) = 1
) AS product
WHERE item.source_shop_id IS NULL
  AND item.tenant_id = product.tenant_id
  AND item.source_product_id = product.weidian_item_id;
