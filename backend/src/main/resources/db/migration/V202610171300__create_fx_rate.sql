-- Reference CNY -> USD rate from the ECB daily feed (design §7.5). Platform-level data shared by all
-- companies, so there is no tenant_id (design §3.4 exception). Append-only: one row per ECB date.
CREATE TABLE crm_fx_rate (
  id BIGINT NOT NULL,
  rate_date DATE NOT NULL,
  base CHAR(3) NOT NULL DEFAULT 'CNY',
  quote CHAR(3) NOT NULL DEFAULT 'USD',
  rate NUMERIC(18,8) NOT NULL,
  source VARCHAR(8) NOT NULL DEFAULT 'ECB',
  fetched_time TIMESTAMPTZ(3) NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_fx_rate PRIMARY KEY (id),
  CONSTRAINT ck_crm_fx_rate_id CHECK (id > 0),
  CONSTRAINT ck_crm_fx_rate_rate CHECK (rate > 0),
  CONSTRAINT ck_crm_fx_rate_base CHECK (base = 'CNY'),
  CONSTRAINT ck_crm_fx_rate_quote CHECK (quote = 'USD'),
  CONSTRAINT ck_crm_fx_rate_source CHECK (source = 'ECB'),
  CONSTRAINT uk_crm_fx_rate_rate_date UNIQUE (rate_date)
);
COMMENT ON TABLE crm_fx_rate IS '参考汇率（人民币兑美元），仅用于达人端显示美元参考金额（设计 §7.5）；平台级数据，各公司共用，不带 tenant_id（设计 §3.4 例外）；每个欧洲央行发布日一行，只追加，省略修改类字段与逻辑删除';
COMMENT ON COLUMN crm_fx_rate.id IS '正数雪花主键';
COMMENT ON COLUMN crm_fx_rate.rate_date IS '汇率日期：欧洲央行发布的参考日期（欧洲中部时间日历日）；全表唯一';
COMMENT ON COLUMN crm_fx_rate.base IS '基准币种：固定 CNY（人民币）';
COMMENT ON COLUMN crm_fx_rate.quote IS '报价币种：固定 USD（美元）';
COMMENT ON COLUMN crm_fx_rate.rate IS '汇率：1 人民币兑多少美元 = 欧洲央行 USD 汇率 ÷ CNY 汇率，保留 8 位小数（四舍五入），必须大于 0';
COMMENT ON COLUMN crm_fx_rate.source IS '数据来源：ECB 欧洲央行每日参考汇率';
COMMENT ON COLUMN crm_fx_rate.fetched_time IS '拉取到该汇率的时间（UTC）';
COMMENT ON COLUMN crm_fx_rate.create_by IS '创建人 ID；由定时任务写入，固定为空';
COMMENT ON COLUMN crm_fx_rate.create_time IS '创建时间（UTC）';
