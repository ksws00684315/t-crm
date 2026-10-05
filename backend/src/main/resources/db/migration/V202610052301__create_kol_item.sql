-- Task 05: exclusive-item (kol item) mappings.
-- Intentionally omits target_url/track_id (task 06) and source (task 09).

CREATE TABLE crm_kol_item (
  id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_item_id VARCHAR(32) NOT NULL,
  valid_from TIMESTAMPTZ(3) NOT NULL,
  valid_to TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_kol_item PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_item_id CHECK (id > 0),
  CONSTRAINT ck_crm_kol_item_weidian_id CHECK (weidian_item_id ~ '^[0-9]{1,32}$'),
  CONSTRAINT ck_crm_kol_item_period CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
-- One current mapping per exclusive item; one current mapping per kol+product.
CREATE UNIQUE INDEX uk_crm_kol_item_shop_item ON crm_kol_item (shop_id, weidian_item_id) WHERE valid_to IS NULL;
CREATE UNIQUE INDEX uk_crm_kol_item_kol_product ON crm_kol_item (kol_id, product_id) WHERE valid_to IS NULL;
-- weidian_item_id leading so item-only and shop+item (+ history by order time) use the same index.
CREATE INDEX idx_crm_kol_item_item_shop_from ON crm_kol_item (weidian_item_id, shop_id, valid_from);
-- List/filter by kol or product, ordered by valid_from DESC, id DESC.
CREATE INDEX idx_crm_kol_item_kol ON crm_kol_item (kol_id, valid_from DESC, id DESC);
CREATE INDEX idx_crm_kol_item_product ON crm_kol_item (product_id, valid_from DESC, id DESC);
COMMENT ON TABLE crm_kol_item IS '专属商品对应关系；只会被置失效，永不删除（无 is_deleted）；不含 target_url/track_id（任务 06）与 source（任务 09）；雪花 ID';
COMMENT ON COLUMN crm_kol_item.id IS '正数雪花主键';
COMMENT ON COLUMN crm_kol_item.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_kol_item.product_id IS '原商品 crm_product.id';
COMMENT ON COLUMN crm_kol_item.shop_id IS '专属商品所在店铺 crm_shop.id';
COMMENT ON COLUMN crm_kol_item.weidian_item_id IS '专属商品微店 ID，数字字符串 1～32 位';
COMMENT ON COLUMN crm_kol_item.valid_from IS '生效开始时间（UTC）';
COMMENT ON COLUMN crm_kol_item.valid_to IS '失效时间（UTC）；空表示当前有效';
COMMENT ON COLUMN crm_kol_item.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_item.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_kol_item.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_kol_item.update_time IS '修改时间（UTC）';
