-- Task 144: Beijing-day sales/refund rollups and durable recalculation state.
CREATE TABLE agg_stats_daily (
  id BIGINT NOT NULL CHECK (id > 0),
  tenant_id BIGINT NOT NULL,
  stat_date DATE NOT NULL,
  grain VARCHAR(8) NOT NULL CHECK (grain IN ('ORDER', 'ITEM')),
  shop_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  product_code VARCHAR(32) NOT NULL,
  sku_code VARCHAR(32) NOT NULL,
  paid_orders BIGINT NOT NULL,
  units BIGINT NOT NULL,
  sales NUMERIC(20,2) NOT NULL,
  cohort_refunds NUMERIC(20,2) NOT NULL,
  completed_refunds NUMERIC(20,2) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_agg_stats_daily PRIMARY KEY (id),
  CONSTRAINT uk_agg_stats_daily_dimensions UNIQUE
    (tenant_id, stat_date, grain, shop_id, kol_id, owner_id, product_code, sku_code)
);
COMMENT ON TABLE agg_stats_daily IS '经营分析日汇总；按租户和日期在事务内整批替换，无修改人和逻辑删除字段';
COMMENT ON COLUMN agg_stats_daily.id IS '雪花主键；重算时重新生成';
COMMENT ON COLUMN agg_stats_daily.tenant_id IS '所属公司';
COMMENT ON COLUMN agg_stats_daily.stat_date IS '北京时间自然日';
COMMENT ON COLUMN agg_stats_daily.grain IS 'ORDER 订单级含运费；ITEM 商品行级不含运费，两者不能混加';
COMMENT ON COLUMN agg_stats_daily.shop_id IS '原交易所属店铺';
COMMENT ON COLUMN agg_stats_daily.kol_id IS '订单或商品行归属达人，0 表示无归属';
COMMENT ON COLUMN agg_stats_daily.owner_id IS '支付时商务归属快照，0 表示无归属';
COMMENT ON COLUMN agg_stats_daily.product_code IS '店铺微店商品标识，订单粒度为空字符串';
COMMENT ON COLUMN agg_stats_daily.sku_code IS '店铺 SKU 标识，订单粒度或缺失时为空字符串';
COMMENT ON COLUMN agg_stats_daily.paid_orders IS '订单粒度的支付订单数；商品粒度为 0，商品的区间去重订单数由事实明细查询';
COMMENT ON COLUMN agg_stats_daily.units IS '支付商品件数；按订单维度或归属商品行维度分别统计，不可混加';
COMMENT ON COLUMN agg_stats_daily.sales IS '支付日归期销售额，人民币元';
COMMENT ON COLUMN agg_stats_daily.cohort_refunds IS '支付日归期且在 30 天内完成的同批退款，人民币元';
COMMENT ON COLUMN agg_stats_daily.completed_refunds IS '退款完成日归期的成功退款，人民币元';
COMMENT ON COLUMN agg_stats_daily.create_time IS '本次汇总生成时间 UTC';

CREATE TABLE agg_stats_dirty_day (
  id BIGINT NOT NULL CHECK (id > 0),
  tenant_id BIGINT NOT NULL,
  stat_date DATE NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_agg_stats_dirty_day PRIMARY KEY (id),
  CONSTRAINT uk_agg_stats_dirty_day_tenant_date UNIQUE (tenant_id, stat_date)
);
COMMENT ON TABLE agg_stats_dirty_day IS '经营分析待重算日期，重算成功后在同一事务物理清理，无修改字段或逻辑删除字段';
COMMENT ON COLUMN agg_stats_dirty_day.id IS '雪花主键';
COMMENT ON COLUMN agg_stats_dirty_day.tenant_id IS '所属公司';
COMMENT ON COLUMN agg_stats_dirty_day.stat_date IS '需重算的北京时间自然日';
COMMENT ON COLUMN agg_stats_dirty_day.create_time IS '入队时间 UTC';

CREATE TABLE agg_stats_checkpoint (
  id BIGINT NOT NULL CHECK (id > 0),
  tenant_id BIGINT NOT NULL,
  scanned_until TIMESTAMPTZ(3) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_agg_stats_checkpoint PRIMARY KEY (id),
  CONSTRAINT uk_agg_stats_checkpoint_tenant UNIQUE (tenant_id)
);
COMMENT ON TABLE agg_stats_checkpoint IS '经营分析扫描水位；系统任务维护，每公司一条，无人工审计和逻辑删除字段';
COMMENT ON COLUMN agg_stats_checkpoint.id IS '雪花主键';
COMMENT ON COLUMN agg_stats_checkpoint.tenant_id IS '所属公司';
COMMENT ON COLUMN agg_stats_checkpoint.scanned_until IS '已持久化脏日期的源数据更新扫描上界 UTC';
COMMENT ON COLUMN agg_stats_checkpoint.create_time IS '扫描水位初始化时间 UTC';
COMMENT ON COLUMN agg_stats_checkpoint.update_time IS '最近推进扫描水位的时间 UTC';
