-- Task 39: latest cleanup row of each mapping (newest cleanup_time, then id).

CREATE INDEX idx_crm_kol_cleanup_item_kol_item
  ON crm_kol_cleanup_item (kol_item_id, cleanup_time DESC, id DESC)
  WHERE is_deleted = FALSE;

COMMENT ON INDEX idx_crm_kol_cleanup_item_kol_item IS '达人商品页取每条对应关系最近一次清理记录';
