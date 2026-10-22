ALTER TABLE crm_shop
  ADD COLUMN shop_code VARCHAR(16),
  ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'WEIDIAN',
  ADD COLUMN max_items INTEGER NOT NULL DEFAULT 20000,
  ADD COLUMN current_items INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN items_refreshed_at TIMESTAMPTZ(3),
  ADD COLUMN is_source BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN copy_pool_status VARCHAR(16) NOT NULL DEFAULT 'EXITED',
  ADD COLUMN product_api_ok BOOLEAN,
  ADD COLUMN product_api_checked_at TIMESTAMPTZ(3),
  ADD COLUMN product_api_error VARCHAR(500),
  ADD COLUMN order_api_ok BOOLEAN,
  ADD COLUMN order_api_checked_at TIMESTAMPTZ(3),
  ADD COLUMN order_api_error VARCHAR(500),
  ADD COLUMN refund_api_ok BOOLEAN,
  ADD COLUMN refund_api_checked_at TIMESTAMPTZ(3),
  ADD COLUMN refund_api_error VARCHAR(500);

WITH numbered AS (
  SELECT id, tenant_id, ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY create_time, id) AS ordinal
  FROM crm_shop
)
UPDATE crm_shop shop
SET shop_code = 'SHOP-' || LPAD((99999 + numbered.ordinal)::TEXT, 6, '0'),
    copy_pool_status = CASE WHEN shop.copy_target THEN 'JOINED' ELSE 'EXITED' END
FROM numbered
WHERE numbered.id = shop.id;

ALTER TABLE crm_shop ALTER COLUMN shop_code SET NOT NULL;
ALTER TABLE crm_shop DROP COLUMN copy_target;
DROP INDEX IF EXISTS uk_crm_shop_copy_target;
CREATE UNIQUE INDEX uk_crm_shop_tenant_shop_code ON crm_shop (tenant_id, shop_code);
CREATE INDEX idx_crm_shop_copy_pool ON crm_shop (tenant_id, channel, copy_pool_status, status)
  WHERE is_deleted = FALSE;
ALTER TABLE crm_shop
  ADD CONSTRAINT ck_crm_shop_channel CHECK (channel IN ('WEIDIAN')),
  ADD CONSTRAINT ck_crm_shop_shop_code CHECK (shop_code ~ '^SHOP-[0-9]{6}$'),
  ADD CONSTRAINT ck_crm_shop_max_items CHECK (max_items BETWEEN 1 AND 1000000),
  ADD CONSTRAINT ck_crm_shop_current_items CHECK (current_items >= 0),
  ADD CONSTRAINT ck_crm_shop_copy_pool_status CHECK (copy_pool_status IN ('JOINED', 'EXITED'));

COMMENT ON COLUMN crm_shop.shop_code IS '公司内店铺编码：SHOP- 加六位数字；创建后不可修改';
COMMENT ON COLUMN crm_shop.channel IS '店铺渠道：WEIDIAN（微店）';
COMMENT ON COLUMN crm_shop.max_items IS '最大商品容量，范围 1～1000000';
COMMENT ON COLUMN crm_shop.current_items IS '当前在售商品数量，刷新或复制成功后更新';
COMMENT ON COLUMN crm_shop.items_refreshed_at IS '最近一次成功刷新商品容量的时间（UTC），空表示尚未刷新';
COMMENT ON COLUMN crm_shop.is_source IS '是否为源店铺；源店铺不参与自动复制';
COMMENT ON COLUMN crm_shop.copy_pool_status IS '自动复制池状态：JOINED 已加入、EXITED 已退出；源店铺显示不参与';
COMMENT ON COLUMN crm_shop.product_api_ok IS '最近一次商品接口检查结果；空表示尚未检查';
COMMENT ON COLUMN crm_shop.product_api_checked_at IS '最近一次商品接口检查时间（UTC）';
COMMENT ON COLUMN crm_shop.product_api_error IS '最近一次商品接口检查错误摘要';
COMMENT ON COLUMN crm_shop.order_api_ok IS '最近一次订单接口检查结果；空表示尚未检查';
COMMENT ON COLUMN crm_shop.order_api_checked_at IS '最近一次订单接口检查时间（UTC）';
COMMENT ON COLUMN crm_shop.order_api_error IS '最近一次订单接口检查错误摘要';
COMMENT ON COLUMN crm_shop.refund_api_ok IS '最近一次退款接口检查结果；空表示尚未检查';
COMMENT ON COLUMN crm_shop.refund_api_checked_at IS '最近一次退款接口检查时间（UTC）';
COMMENT ON COLUMN crm_shop.refund_api_error IS '最近一次退款接口检查错误摘要';
COMMENT ON TABLE crm_shop IS '公司店铺及自动复制容量；每家公司默认最多 20 个，雪花 ID、逻辑删除';

UPDATE sys_config SET config_value = '20', update_time = NOW()
WHERE config_key = 'shop.max-per-company' AND config_value = '3';
