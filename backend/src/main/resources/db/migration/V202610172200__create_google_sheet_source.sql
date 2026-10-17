-- Product source sheets read through the Google Sheets API (ADR-021). No foreign keys by convention.

CREATE TABLE crm_google_credential (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  client_email VARCHAR(255) NOT NULL,
  private_key_id VARCHAR(64) NOT NULL,
  private_key_cipher TEXT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_google_credential PRIMARY KEY (id),
  CONSTRAINT ck_crm_google_credential_id CHECK (id > 0),
  CONSTRAINT ck_crm_google_credential_tenant_id CHECK (tenant_id > 0)
);
CREATE UNIQUE INDEX uk_crm_google_credential_tenant ON crm_google_credential (tenant_id);
COMMENT ON TABLE crm_google_credential IS '公司级 Google 服务账号；私钥使用 AES-256-GCM 密文保存，只写不读；雪花 ID、逻辑删除';
COMMENT ON COLUMN crm_google_credential.id IS '雪花主键';
COMMENT ON COLUMN crm_google_credential.tenant_id IS '所属公司；每家公司最多一个服务账号';
COMMENT ON COLUMN crm_google_credential.client_email IS '服务账号邮箱；客户把源表分享给该邮箱';
COMMENT ON COLUMN crm_google_credential.private_key_id IS '密钥 ID（JWT 头部 kid）；不是密钥';
COMMENT ON COLUMN crm_google_credential.private_key_cipher IS 'PKCS#8 私钥 PEM 的密文；SecretCipher v1 格式';
COMMENT ON COLUMN crm_google_credential.create_by IS '创建人 ID';
COMMENT ON COLUMN crm_google_credential.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_google_credential.update_by IS '最后修改人 ID';
COMMENT ON COLUMN crm_google_credential.update_time IS '最后修改时间（UTC）';
COMMENT ON COLUMN crm_google_credential.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_sheet_source (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  source_name VARCHAR(64) NOT NULL,
  spreadsheet_id VARCHAR(128) NOT NULL,
  sheet_title VARCHAR(100) NOT NULL,
  header_row INTEGER NOT NULL,
  link_column VARCHAR(3) NOT NULL,
  name_column VARCHAR(3) NOT NULL,
  price_column VARCHAR(3) NOT NULL,
  category_column VARCHAR(3),
  image_column VARCHAR(3),
  default_shop_id BIGINT,
  price_currency CHAR(3) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  read_no INTEGER NOT NULL DEFAULT 0,
  last_read_at TIMESTAMPTZ(3),
  last_task_id BIGINT,
  last_row_count INTEGER,
  last_skipped TEXT,
  last_error VARCHAR(255),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_sheet_source PRIMARY KEY (id),
  CONSTRAINT ck_crm_sheet_source_id CHECK (id > 0),
  CONSTRAINT ck_crm_sheet_source_spreadsheet CHECK (spreadsheet_id ~ '^[A-Za-z0-9_-]{20,128}$'),
  CONSTRAINT ck_crm_sheet_source_header_row CHECK (header_row BETWEEN 1 AND 100),
  CONSTRAINT ck_crm_sheet_source_columns CHECK (
    link_column ~ '^[A-Z]{1,3}$' AND name_column ~ '^[A-Z]{1,3}$' AND price_column ~ '^[A-Z]{1,3}$'
    AND (category_column IS NULL OR category_column ~ '^[A-Z]{1,3}$')
    AND (image_column IS NULL OR image_column ~ '^[A-Z]{1,3}$')),
  CONSTRAINT ck_crm_sheet_source_currency CHECK (price_currency ~ '^[A-Z]{3}$'),
  CONSTRAINT ck_crm_sheet_source_status CHECK (status IN (0, 1)),
  CONSTRAINT ck_crm_sheet_source_read_no CHECK (read_no >= 0)
);
CREATE INDEX idx_crm_sheet_source_tenant ON crm_sheet_source (tenant_id, id) WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_sheet_source IS '原商品源表（Google 表格）配置与最近一次读取结果（ADR-021）；雪花 ID、逻辑删除';
COMMENT ON COLUMN crm_sheet_source.id IS '雪花主键';
COMMENT ON COLUMN crm_sheet_source.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_sheet_source.source_name IS '源表名称，公司内显示用';
COMMENT ON COLUMN crm_sheet_source.spreadsheet_id IS 'Google 表格 ID（从表格地址解析）';
COMMENT ON COLUMN crm_sheet_source.sheet_title IS '页签名称';
COMMENT ON COLUMN crm_sheet_source.header_row IS '表头所在行（1 起），数据从下一行开始';
COMMENT ON COLUMN crm_sheet_source.link_column IS '商品链接所在列字母';
COMMENT ON COLUMN crm_sheet_source.name_column IS '商品名称所在列字母';
COMMENT ON COLUMN crm_sheet_source.price_column IS '展示价所在列字母';
COMMENT ON COLUMN crm_sheet_source.category_column IS '分类所在列字母；空表示不读取';
COMMENT ON COLUMN crm_sheet_source.image_column IS '图片（=IMAGE 公式）所在列字母；空表示不读取';
COMMENT ON COLUMN crm_sheet_source.default_shop_id IS '默认店铺（crm_shop.id）；链接中没有店铺 ID 时使用，空表示必须从链接取得';
COMMENT ON COLUMN crm_sheet_source.price_currency IS '源表价格的货币，ISO 4217';
COMMENT ON COLUMN crm_sheet_source.status IS '1 启用 / 0 停用；停用后不能读取';
COMMENT ON COLUMN crm_sheet_source.read_no IS '已开始的读取次数；源表商品快照用它判断是否仍在表中';
COMMENT ON COLUMN crm_sheet_source.last_read_at IS '最近一次读取成功提交导入任务的时间（UTC）';
COMMENT ON COLUMN crm_sheet_source.last_task_id IS '最近一次读取生成的导入任务（crm_task.id）';
COMMENT ON COLUMN crm_sheet_source.last_row_count IS '最近一次读取提交的商品行数';
COMMENT ON COLUMN crm_sheet_source.last_skipped IS '最近一次读取跳过的行：JSON，按原因计数并附最多 100 行样例（行号、原因、名称）；空链接行只计数';
COMMENT ON COLUMN crm_sheet_source.last_error IS '最近一次读取失败的错误码；成功时清空';
COMMENT ON COLUMN crm_sheet_source.create_by IS '创建人 ID';
COMMENT ON COLUMN crm_sheet_source.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_sheet_source.update_by IS '最后修改人 ID';
COMMENT ON COLUMN crm_sheet_source.update_time IS '最后修改时间（UTC）';
COMMENT ON COLUMN crm_sheet_source.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_sheet_source_item (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_item_id VARCHAR(32) NOT NULL,
  sheet_row INTEGER NOT NULL,
  last_read_no INTEGER NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_sheet_source_item PRIMARY KEY (id),
  CONSTRAINT ck_crm_sheet_source_item_id CHECK (id > 0),
  CONSTRAINT ck_crm_sheet_source_item_weidian_id CHECK (weidian_item_id ~ '^[0-9]{1,32}$'),
  CONSTRAINT ck_crm_sheet_source_item_row CHECK (sheet_row > 0)
);
CREATE UNIQUE INDEX uk_crm_sheet_source_item ON crm_sheet_source_item (source_id, shop_id, weidian_item_id);
CREATE INDEX idx_crm_sheet_source_item_read ON crm_sheet_source_item (source_id, last_read_no);
COMMENT ON TABLE crm_sheet_source_item IS '源表中读到过的商品；last_read_no 小于源表 read_no 表示已不在表中（ADR-021 第 7 条）；不做逻辑删除';
COMMENT ON COLUMN crm_sheet_source_item.id IS '雪花主键';
COMMENT ON COLUMN crm_sheet_source_item.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_sheet_source_item.source_id IS '源表（crm_sheet_source.id）';
COMMENT ON COLUMN crm_sheet_source_item.shop_id IS '店铺（crm_shop.id）';
COMMENT ON COLUMN crm_sheet_source_item.weidian_item_id IS '原商品微店商品 ID';
COMMENT ON COLUMN crm_sheet_source_item.sheet_row IS '最近一次读到时所在的表格行号';
COMMENT ON COLUMN crm_sheet_source_item.last_read_no IS '最近一次读到该商品的读取序号';
COMMENT ON COLUMN crm_sheet_source_item.create_time IS '首次读到的时间（UTC）';
COMMENT ON COLUMN crm_sheet_source_item.update_time IS '最近读到的时间（UTC）';

ALTER TABLE crm_product ADD COLUMN price_currency CHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE crm_product ADD CONSTRAINT ck_crm_product_price_currency CHECK (price_currency ~ '^[A-Z]{3}$');
COMMENT ON COLUMN crm_product.price_currency IS '展示价货币，ISO 4217；Excel 导入与手工录入为 CNY，源表读取取源表配置（ADR-021）';
