-- Task 76 / ADR-032 2026-10-28 revision: Weidian settlement detail flows (shop.order.trade.detail)
-- stored verbatim per order; commission basis verification reads them (design §5.2 / §8.3).

CREATE TABLE crm_order_settlement_flow (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_order_id VARCHAR(64) NOT NULL,
  order_id BIGINT,
  refund_id VARCHAR(64),
  trade_type VARCHAR(32),
  trade_sub_type VARCHAR(32),
  trade_time TIMESTAMPTZ(3),
  remark VARCHAR(512),
  amount_raw NUMERIC(18,4),
  amount_cny NUMERIC(14,2),
  query_type VARCHAR(32) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  first_pulled_at TIMESTAMPTZ(3) NOT NULL,
  last_pulled_at TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_order_settlement_flow PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_settlement_flow_id CHECK (id > 0),
  CONSTRAINT ck_crm_order_settlement_flow_query CHECK (query_type IN ('INCOME_DETAIL', 'PRE_SETTLE_REFUND'))
);
-- The API has no flow id, so the dedup key is a SHA-256 fingerprint over shop + queryType + orderId
-- + refundId + tradeType + tradeSubType + tradeTime + raw amount + remark; two rows whose fields are
-- all equal merge into one (known limitation, design §5.2).
CREATE UNIQUE INDEX uk_crm_order_settlement_flow_fp ON crm_order_settlement_flow (tenant_id, fingerprint);
CREATE INDEX idx_crm_order_settlement_flow_order ON crm_order_settlement_flow (tenant_id, shop_id, weidian_order_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_order_settlement_flow_matched ON crm_order_settlement_flow (tenant_id, order_id) WHERE order_id IS NOT NULL AND is_deleted = FALSE;
COMMENT ON TABLE crm_order_settlement_flow IS '微店订单结算明细流水（shop.order.trade.detail，任务 76 / ADR-032 2026-10-28 修订）：只追加、按指纹去重；计佣基数核实按店铺+订单号完全匹配读取本表';
COMMENT ON COLUMN crm_order_settlement_flow.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order_settlement_flow.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_order_settlement_flow.shop_id IS 'CRM 店铺 crm_shop.id';
COMMENT ON COLUMN crm_order_settlement_flow.weidian_order_id IS '微店结算明细回传的 orderId 原值；与 crm_order.weidian_order_id 完全相等才算匹配，不截断后缀';
COMMENT ON COLUMN crm_order_settlement_flow.order_id IS '拉取时匹配到的本库订单 crm_order.id；订单晚于流水入库时先为空，订单保存时会回填；计佣核实按订单号实时匹配，不依赖本列';
COMMENT ON COLUMN crm_order_settlement_flow.refund_id IS '微店退款单号 refundId 原值；无退款为空';
COMMENT ON COLUMN crm_order_settlement_flow.trade_type IS '流水类型 tradeType 原值（开放平台文档示例为收入/支出）；接口未返回为空，视为未知类型，整单不核实';
COMMENT ON COLUMN crm_order_settlement_flow.trade_sub_type IS '流水子类型 tradeSubType 原值（文档示例：货款收入、交易手续费、分润、退款等）；未知取值整单不核实';
COMMENT ON COLUMN crm_order_settlement_flow.trade_time IS '流水时间 tradeTime（epoch 毫秒转 UTC）；「货款收入」一笔的时间即订单结算时间';
COMMENT ON COLUMN crm_order_settlement_flow.remark IS '流水备注原值';
COMMENT ON COLUMN crm_order_settlement_flow.amount_raw IS '接口返回的 amount 原始值，不做换算；单位常量待 M0 第 11 步确认（默认按分）';
COMMENT ON COLUMN crm_order_settlement_flow.amount_cny IS '按单位常量换算后的人民币金额（分转元保留两位小数）；amount 缺失或不可解析为空';
COMMENT ON COLUMN crm_order_settlement_flow.query_type IS '查询类型：INCOME_DETAIL 收支明细（含部分退款）/ PRE_SETTLE_REFUND 结算前全额退款';
COMMENT ON COLUMN crm_order_settlement_flow.fingerprint IS '幂等指纹 SHA-256：店铺+queryType+orderId+refundId+tradeType+tradeSubType+tradeTime+amount 原值+remark；重复拉取不产生重复行';
COMMENT ON COLUMN crm_order_settlement_flow.first_pulled_at IS '首次拉取到本流水的时间 UTC';
COMMENT ON COLUMN crm_order_settlement_flow.last_pulled_at IS '最近一次拉取仍观察到本流水的时间 UTC';
COMMENT ON COLUMN crm_order_settlement_flow.create_by IS '创建人；系统同步为空';
COMMENT ON COLUMN crm_order_settlement_flow.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_order_settlement_flow.update_by IS '最近修改人；系统同步为空';
COMMENT ON COLUMN crm_order_settlement_flow.update_time IS '最近修改时间 UTC';
COMMENT ON COLUMN crm_order_settlement_flow.is_deleted IS '逻辑删除；目前无删除路径';
