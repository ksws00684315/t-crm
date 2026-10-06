-- Task 06: append-only click log, declaratively partitioned by month on click_time (UTC).
-- id is derived from the Redis Stream message ID (task 06 §4.5), so redelivered messages collide
-- on the primary key and are skipped with ON CONFLICT DO NOTHING.

CREATE TABLE crm_click_log (
  id BIGINT NOT NULL,
  click_time TIMESTAMPTZ(3) NOT NULL,
  kol_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  kol_item_id BIGINT NOT NULL,
  ip_hash CHAR(64) NOT NULL,
  ua VARCHAR(512) NOT NULL DEFAULT '',
  referer VARCHAR(512) NOT NULL DEFAULT '',
  country VARCHAR(8) NOT NULL DEFAULT '',
  is_bot BOOLEAN NOT NULL DEFAULT FALSE,
  is_dup BOOLEAN NOT NULL DEFAULT FALSE,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_click_log PRIMARY KEY (id, click_time),
  CONSTRAINT ck_crm_click_log_id CHECK (id > 0)
) PARTITION BY RANGE (click_time);
-- Click counts per link (KOL + original product) over a time range (task 06 §5); a link keeps its
-- clicks when the exclusive item changes. Created on the parent, inherited by partitions.
CREATE INDEX idx_crm_click_log_kol_product_time ON crm_click_log (kol_id, product_id, click_time);
COMMENT ON TABLE crm_click_log IS '点击日志；只追加，省略修改类字段与逻辑删除；按 click_time（UTC）每月一个分区 crm_click_log_yyyymm，保留 13 个月；主键 (id, click_time)，id 由 Redis Stream 消息 ID 确定性生成（毫秒时间戳 × 1000000 + 序号）';
COMMENT ON COLUMN crm_click_log.id IS '正数主键：Stream 消息 ID 的毫秒时间戳 × 1000000 + 序号；重复投递时相同';
COMMENT ON COLUMN crm_click_log.click_time IS '点击时间（UTC，跳转服务写入事件的 ts）；分区键';
COMMENT ON COLUMN crm_click_log.kol_id IS '达人 crm_kol.id（由 kol_item_id 查得）';
COMMENT ON COLUMN crm_click_log.product_id IS '原商品 crm_product.id（由 kol_item_id 查得）';
COMMENT ON COLUMN crm_click_log.kol_item_id IS '点击时的对应关系 crm_kol_item.id（记录当时的专属商品，供排查；点击数按 kol_id + product_id 统计）';
COMMENT ON COLUMN crm_click_log.ip_hash IS '客户端 IP 的加盐 SHA-256（十六进制 64 位）；不存明文 IP';
COMMENT ON COLUMN crm_click_log.ua IS 'User-Agent，截断到 512 字符；空串表示请求未带';
COMMENT ON COLUMN crm_click_log.referer IS 'Referer，截断到 512 字符；空串表示请求未带';
COMMENT ON COLUMN crm_click_log.country IS 'Cloudflare CF-IPCountry，截断到 8 字符；空串表示未知';
COMMENT ON COLUMN crm_click_log.is_bot IS '机器人：UA 为空或含爬虫、社媒预览关键字；统计时排除';
COMMENT ON COLUMN crm_click_log.is_dup IS '重复点击：同一对应关系同一 IP 哈希与上一次点击相差小于 10 秒；统计时排除';
COMMENT ON COLUMN crm_click_log.create_by IS '创建人 ID；由点击消费写入，恒为空';
COMMENT ON COLUMN crm_click_log.create_time IS '写入时间（UTC）';

-- Current and next month partitions (UTC); later months are created by the daily maintenance job.
DO $$
DECLARE
  first_month DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
  month_start DATE;
  month_end DATE;
  partition_name TEXT;
BEGIN
  FOR i IN 0..1 LOOP
    month_start := (first_month + make_interval(months => i))::date;
    month_end := (first_month + make_interval(months => i + 1))::date;
    partition_name := 'crm_click_log_' || to_char(month_start, 'YYYYMM');
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF crm_click_log FOR VALUES FROM (%L) TO (%L)',
      partition_name, month_start || ' 00:00:00+00', month_end || ' 00:00:00+00');
    EXECUTE format(
      'COMMENT ON TABLE %I IS %L',
      partition_name, 'crm_click_log 的 ' || to_char(month_start, 'YYYY-MM') || '（UTC）月分区');
  END LOOP;
END
$$;
