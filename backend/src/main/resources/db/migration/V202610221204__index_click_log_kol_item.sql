-- Task 39: visit counts are clicks of one mapping, excluding bots and duplicate clicks.
-- Created on the partitioned parent so existing and future monthly partitions inherit it.

CREATE INDEX idx_crm_click_log_kol_item
  ON crm_click_log (kol_item_id)
  WHERE NOT is_bot AND NOT is_dup;

COMMENT ON INDEX idx_crm_click_log_kol_item IS '达人商品详情按对应关系统计有效访问次数';
