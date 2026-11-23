CREATE TABLE crm_order_receivable_event (
  id BIGINT GENERATED ALWAYS AS IDENTITY,
  tenant_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  business_at TIMESTAMPTZ(3),
  recorded_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_crm_order_receivable_event PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_receivable_event_type CHECK (
    event_type IN ('PRE_INCOME', 'PRE_INCOME_CHANGE', 'PRE_INCOME_CLOSED', 'PRE_INCOME_PAID')
  )
);
CREATE INDEX idx_crm_order_receivable_event_order
  ON crm_order_receivable_event (tenant_id, order_id, id);

COMMENT ON TABLE crm_order_receivable_event IS '已观察到的微店订单预收入变动，只追加';
COMMENT ON COLUMN crm_order_receivable_event.id IS '事件序号';
COMMENT ON COLUMN crm_order_receivable_event.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_order_receivable_event.order_id IS '本地订单';
COMMENT ON COLUMN crm_order_receivable_event.event_type IS 'PRE_INCOME 初次确认预收入；PRE_INCOME_CHANGE 改价；PRE_INCOME_CLOSED 关闭冲销；PRE_INCOME_PAID 支付转出';
COMMENT ON COLUMN crm_order_receivable_event.amount_cny IS '预收入增减额，元';
COMMENT ON COLUMN crm_order_receivable_event.business_at IS '来源业务时间，未知时为空';
COMMENT ON COLUMN crm_order_receivable_event.recorded_at IS '系统记录时间';
