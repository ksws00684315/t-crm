-- Task 159 / 《财务流转口径规则》（任务 158）：财务流水台账，按事件记科目（收入侧订单级、佣金侧商品行级）。
-- 只追加、按来源键幂等；佣金余额与状态仍以 crm_commission_wallet / crm_commission_item_state 为唯一事实源，
-- 本表只作口径记录与呈现，不参与余额计算。

CREATE TABLE crm_finance_flow (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  order_id BIGINT,
  order_item_id BIGINT,
  kol_id BIGINT,
  subject VARCHAR(32) NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id BIGINT,
  source_key VARCHAR(128) NOT NULL,
  flow_no VARCHAR(40) NOT NULL,
  evidence VARCHAR(255),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_finance_flow PRIMARY KEY (id),
  CONSTRAINT ck_crm_finance_flow_id CHECK (id > 0),
  CONSTRAINT ck_crm_finance_flow_subject CHECK (subject IN ('PRE_INCOME', 'FROZEN_INCOME', 'OUTFLOW', 'WITHDRAWABLE_INCOME', 'COMMISSION_FROZEN', 'COMMISSION_CLAWBACK', 'COMMISSION_SETTLEABLE', 'ORDER_WITHDRAWN', 'COMMISSION_WITHDRAWN')),
  CONSTRAINT ck_crm_finance_flow_source CHECK (source_type IN ('ORDER', 'REFUND', 'SETTLEMENT', 'REVISION', 'RELEASE', 'WITHDRAWAL', 'WITHDRAWAL_ALLOCATION'))
);
-- 幂等键：一次业务事实一行；重复同步、重复结算、重复付款登记靠唯一索引去重
CREATE UNIQUE INDEX uk_crm_finance_flow_source ON crm_finance_flow (tenant_id, source_key);
CREATE UNIQUE INDEX uk_crm_finance_flow_no ON crm_finance_flow (tenant_id, flow_no);
CREATE INDEX idx_crm_finance_flow_order ON crm_finance_flow (tenant_id, order_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_finance_flow_kol ON crm_finance_flow (tenant_id, kol_id) WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_finance_flow IS '财务流水台账（任务 159 / 财务流转口径规则 任务 158）：按事件记科目，只追加、按来源键幂等；佣金余额与状态以 crm_commission_wallet / crm_commission_item_state 为唯一事实源，本表只作口径记录与呈现';
COMMENT ON COLUMN crm_finance_flow.id IS '正数雪花主键';
COMMENT ON COLUMN crm_finance_flow.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_finance_flow.order_id IS '关联订单 crm_order.id；达人级汇总（佣金已提现）为空';
COMMENT ON COLUMN crm_finance_flow.order_item_id IS '关联商品行 crm_order_item.id；收入侧订单级科目为空';
COMMENT ON COLUMN crm_finance_flow.kol_id IS '关联达人 crm_kol.id；收入侧科目为空';
COMMENT ON COLUMN crm_finance_flow.subject IS '科目：PRE_INCOME 预收入 / FROZEN_INCOME 冻结中收入 / OUTFLOW 支出 / WITHDRAWABLE_INCOME 可提现收入 / COMMISSION_FROZEN 冻结中佣金支出 / COMMISSION_CLAWBACK 佣金扣回 / COMMISSION_SETTLEABLE 可结算佣金 / ORDER_WITHDRAWN 订单已提现 / COMMISSION_WITHDRAWN 佣金已提现';
COMMENT ON COLUMN crm_finance_flow.amount_cny IS '分录金额（元，两位小数，可负）：正数为科目增加、负数为科目减少（对冲分录）';
COMMENT ON COLUMN crm_finance_flow.occurred_at IS '业务发生时间 UTC：支付/退款/结算/修订/释放/付款登记的事实时间，不得用读取或同步时间代替';
COMMENT ON COLUMN crm_finance_flow.source_type IS '来源事实类型：ORDER 订单（预收入/冻结中收入/支出/可提现收入/关单）/ REFUND 退款单 / SETTLEMENT 结算确认 / REVISION 计佣修订 / RELEASE 佣金释放 / WITHDRAWAL 提现付款 / WITHDRAWAL_ALLOCATION 提现分配';
COMMENT ON COLUMN crm_finance_flow.source_id IS '来源事实 ID：订单/退款单/商品行状态/提现单的主键，按 source_type 解释；可为空';
COMMENT ON COLUMN crm_finance_flow.source_key IS '幂等键：业务事实唯一标识（如 FIN:{orderId}:PRE_INCOME、FIN:{refundId}:OUTFLOW、FIN:{itemStateId}:{sourceVersion} 派生段、FIN:WD:{withdrawalId}），重复触发不产生重复行';
COMMENT ON COLUMN crm_finance_flow.flow_no IS '稳定流水号 FF{雪花ID}，生成后不变，用于呈现与追溯';
COMMENT ON COLUMN crm_finance_flow.evidence IS '溯源备注（可空）：暂计/回冲/关单等记账依据标注，不承载金额';
COMMENT ON COLUMN crm_finance_flow.is_deleted IS '逻辑删除；台账只追加，目前无删除路径';
COMMENT ON COLUMN crm_finance_flow.create_by IS '创建人；系统记账为空';
COMMENT ON COLUMN crm_finance_flow.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_finance_flow.update_by IS '最近修改人；系统记账为空';
COMMENT ON COLUMN crm_finance_flow.update_time IS '最近修改时间 UTC';
