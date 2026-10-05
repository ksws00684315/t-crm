-- Task 05: shops and original products (with product code sequence).
-- Intentionally omits Weidian sync credentials (task 09) and sheet_row (task 07).

CREATE TABLE crm_shop (
  id BIGINT NOT NULL,
  shop_name VARCHAR(64) NOT NULL,
  weidian_shop_id VARCHAR(32) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  remark VARCHAR(500),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_shop PRIMARY KEY (id),
  CONSTRAINT ck_crm_shop_id CHECK (id > 0),
  CONSTRAINT ck_crm_shop_weidian_id CHECK (weidian_shop_id ~ '^[0-9]{1,32}$'),
  CONSTRAINT ck_crm_shop_status CHECK (status IN (0, 1))
);
-- Full-table unique (prompt §4.1); codes never reused across soft deletes.
CREATE UNIQUE INDEX uk_crm_shop_weidian_shop_id ON crm_shop (weidian_shop_id);
COMMENT ON TABLE crm_shop IS '微店店铺；全表最多 3 个（应用层校验）；雪花 ID，逻辑删除；不含同步凭证（任务 09）';
COMMENT ON COLUMN crm_shop.id IS '正数雪花主键';
COMMENT ON COLUMN crm_shop.shop_name IS '店铺名称，1～64 字符';
COMMENT ON COLUMN crm_shop.weidian_shop_id IS '微店店铺 ID，数字字符串 1～32 位；全表唯一（含已删除）；创建后不可修改';
COMMENT ON COLUMN crm_shop.status IS '状态：0 停用、1 启用；停用后不可再导入商品或对应关系';
COMMENT ON COLUMN crm_shop.remark IS '备注；空表示无备注';
COMMENT ON COLUMN crm_shop.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_shop.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_shop.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_shop.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_shop.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE SEQUENCE crm_product_code_seq AS BIGINT START WITH 1 MINVALUE 1 MAXVALUE 99999999 NO CYCLE;
COMMENT ON SEQUENCE crm_product_code_seq IS '原商品编码顺序号；P + 至少 6 位补零，已占用的号码由应用跳过，号码不复用';

CREATE TABLE crm_product (
  id BIGINT NOT NULL,
  product_code VARCHAR(16) NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_item_id VARCHAR(32) NOT NULL,
  name VARCHAR(200) NOT NULL,
  category VARCHAR(64),
  image_url VARCHAR(512),
  price NUMERIC(14,2) NOT NULL,
  rate NUMERIC(6,4),
  status SMALLINT NOT NULL DEFAULT 1,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_product PRIMARY KEY (id),
  CONSTRAINT ck_crm_product_id CHECK (id > 0),
  CONSTRAINT ck_crm_product_code CHECK (product_code ~ '^[A-Z0-9_-]{1,16}$'),
  CONSTRAINT ck_crm_product_weidian_id CHECK (weidian_item_id ~ '^[0-9]{1,32}$'),
  CONSTRAINT ck_crm_product_price CHECK (price >= 0),
  CONSTRAINT ck_crm_product_rate CHECK (rate IS NULL OR (rate >= 0 AND rate <= 1)),
  CONSTRAINT ck_crm_product_status CHECK (status IN (0, 1))
);
-- Codes are never reused, so uniqueness also covers logically deleted rows.
CREATE UNIQUE INDEX uk_crm_product_product_code ON crm_product (product_code);
-- Business unique key among live products.
CREATE UNIQUE INDEX uk_crm_product_shop_item ON crm_product (shop_id, weidian_item_id) WHERE is_deleted = FALSE;
-- Default list order (product_code ASC) and shop / status / category filters.
CREATE INDEX idx_crm_product_shop ON crm_product (shop_id, product_code) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_product_status ON crm_product (status, product_code) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_product_category ON crm_product (category, product_code) WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_product IS '原商品；雪花 ID，逻辑删除；不含表格行序（任务 07）';
COMMENT ON COLUMN crm_product.id IS '正数雪花主键';
COMMENT ON COLUMN crm_product.product_code IS '原商品编码：^[A-Z0-9_-]{1,16}$，存大写；创建后不可修改，全表唯一且不复用（含已删除）';
COMMENT ON COLUMN crm_product.shop_id IS '原商品所在店铺 crm_shop.id';
COMMENT ON COLUMN crm_product.weidian_item_id IS '原商品微店 ID，数字字符串 1～32 位；与 shop_id 在未删除记录中唯一';
COMMENT ON COLUMN crm_product.name IS '商品名称，1～200 字符';
COMMENT ON COLUMN crm_product.category IS '分类；空表示未填写';
COMMENT ON COLUMN crm_product.image_url IS '图片 HTTPS 地址；空表示未填写';
COMMENT ON COLUMN crm_product.price IS '展示价，≥ 0，两位小数';
COMMENT ON COLUMN crm_product.rate IS '商品级佣金比例，小数 0～1；空表示使用达人默认比例';
COMMENT ON COLUMN crm_product.status IS '状态：0 下架、1 在售；导入不修改状态，新增默认为在售';
COMMENT ON COLUMN crm_product.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_product.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_product.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_product.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_product.is_deleted IS '逻辑删除：false 有效、true 已删除';
