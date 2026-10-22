-- Task 39: link code on creator-item mappings. Nullable until the backfill script runs.

ALTER TABLE crm_kol_item ADD COLUMN link_code VARCHAR(16);

COMMENT ON COLUMN crm_kol_item.link_code IS '公司内唯一的链接编码：KPL- 加 12 位大写十六进制；创建后不变，供达人商品页与订单链路追溯';
