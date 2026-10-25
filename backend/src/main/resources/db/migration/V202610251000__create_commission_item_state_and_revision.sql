-- Task 62 / ADR-032 §2: per order-item commission source snapshots and append-only revisions.

CREATE TABLE crm_commission_item_state (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  kol_id BIGINT,
  source_key VARCHAR(160) NOT NULL,
  source_version VARCHAR(160),
  source_updated_at TIMESTAMPTZ(3),
  basis_cny NUMERIC(14,2),
  successful_refund_cny NUMERIC(14,2),
  basis_includes_refund BOOLEAN,
  settlement_state VARCHAR(24) NOT NULL,
  settlement_time TIMESTAMPTZ(3),
  completed_time TIMESTAMPTZ(3),
  after_sales BOOLEAN NOT NULL DEFAULT FALSE,
  verified BOOLEAN NOT NULL DEFAULT FALSE,
  config_version INTEGER,
  rule_version_id BIGINT,
  accrued_commission_cny NUMERIC(14,2) NOT NULL DEFAULT 0,
  released_commission_cny NUMERIC(14,2) NOT NULL DEFAULT 0,
  release_basis VARCHAR(24) NOT NULL,
  release_at TIMESTAMPTZ(3),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_item_state PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_item_state_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_item_state_settlement CHECK (settlement_state IN ('CONFIRMED', 'UNSETTLED', 'UNKNOWN')),
  CONSTRAINT ck_crm_commission_item_state_basis CHECK (release_basis IN ('CONFIRMED', 'FALLBACK', 'UNSETTLED', 'AFTER_SALES', 'UNVERIFIED')),
  CONSTRAINT ck_crm_commission_item_state_amounts CHECK (accrued_commission_cny >= 0 AND released_commission_cny >= 0),
  CONSTRAINT ck_crm_commission_item_state_verified CHECK (
    (verified AND release_basis <> 'UNVERIFIED') OR (NOT verified AND release_basis = 'UNVERIFIED')
  )
);
CREATE UNIQUE INDEX uk_crm_commission_item_state_item ON crm_commission_item_state (tenant_id, order_item_id);
CREATE UNIQUE INDEX uk_crm_commission_item_state_source ON crm_commission_item_state (tenant_id, source_key);
CREATE INDEX idx_crm_commission_item_state_kol_release ON crm_commission_item_state (tenant_id, kol_id, release_at, order_item_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_commission_item_state_order ON crm_commission_item_state (tenant_id, order_id, order_item_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_crm_commission_item_state_due ON crm_commission_item_state (release_at) WHERE verified AND release_at IS NOT NULL AND is_deleted = FALSE;
COMMENT ON TABLE crm_commission_item_state IS '订单商品行计佣来源快照与释放状态（任务 62 / ADR-032）；每商品行一行，只由结算引擎更新；订单商品行被替换删除时逻辑删除，聚合计入 is_deleted = FALSE';
COMMENT ON COLUMN crm_commission_item_state.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_item_state.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_commission_item_state.order_id IS '所属订单 crm_order.id';
COMMENT ON COLUMN crm_commission_item_state.order_item_id IS '订单商品行 crm_order_item.id';
COMMENT ON COLUMN crm_commission_item_state.kol_id IS '归属达人 crm_kol.id；未归属或待人工核实时为空';
COMMENT ON COLUMN crm_commission_item_state.source_key IS '微店商品行稳定来源键 ORDER_ITEM:{order_item_id}';
COMMENT ON COLUMN crm_commission_item_state.source_version IS '当前来源快照版本指纹（MD5）；上线回填行为空，表示结算引擎尚未登记过版本';
COMMENT ON COLUMN crm_commission_item_state.source_updated_at IS '最近一次来源快照同步时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.basis_cny IS '有效金额（微店订单可提现金额，语义 WEIDIAN_COMMISSIONABLE_CNY）；未核实（Q-1017-1/2 待 M0）为空，不以订单实付代替';
COMMENT ON COLUMN crm_commission_item_state.successful_refund_cny IS '来源确认的成功退款合计，元；来源未提供为空';
COMMENT ON COLUMN crm_commission_item_state.basis_includes_refund IS '来源金额是否已扣成功退款；来源未说明为空';
COMMENT ON COLUMN crm_commission_item_state.settlement_state IS '微店结算状态：CONFIRMED 已结算、UNSETTLED 未结算、UNKNOWN 未知（字段待 M0）';
COMMENT ON COLUMN crm_commission_item_state.settlement_time IS '微店结算时间 UTC；未知为空';
COMMENT ON COLUMN crm_commission_item_state.completed_time IS '交易完成时间 UTC；未知为空';
COMMENT ON COLUMN crm_commission_item_state.after_sales IS '售后未完结标记（退款 PROCESSING/SUCCESS/UNKNOWN）；阻止释放与提现占用';
COMMENT ON COLUMN crm_commission_item_state.verified IS '核实通过（有效金额语义 + 达人归属 + 支付时刻比例齐备）；未核实不计提不释放';
COMMENT ON COLUMN crm_commission_item_state.config_version IS '支付时刻生效的推广配置版本号 crm_kol_promotion_record.version_no（ADR-032 §3）';
COMMENT ON COLUMN crm_commission_item_state.rule_version_id IS '支付时刻生效的结算配置版本 crm_commission_rule_version.id；只有默认规则时为空';
COMMENT ON COLUMN crm_commission_item_state.accrued_commission_cny IS '累计净计佣 = round_half_up(有效金额×比例,2)；未核实为 0';
COMMENT ON COLUMN crm_commission_item_state.released_commission_cny IS '已释放入钱包合计；归属变更后被回冲重置';
COMMENT ON COLUMN crm_commission_item_state.release_basis IS '释放依据：CONFIRMED 按结算时间、FALLBACK 完成后+冻结天数、UNSETTLED 未结算、AFTER_SALES 售后阻断、UNVERIFIED 未核实';
COMMENT ON COLUMN crm_commission_item_state.release_at IS '预计或实际释放时间 UTC；不释放为空';
COMMENT ON COLUMN crm_commission_item_state.create_by IS '创建人；系统同步为空';
COMMENT ON COLUMN crm_commission_item_state.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.update_by IS '最近修改人；系统同步为空';
COMMENT ON COLUMN crm_commission_item_state.update_time IS '最近修改时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.is_deleted IS '逻辑删除：订单商品行被替换删除后，快照不再参与计佣、释放与占用';

CREATE TABLE crm_commission_item_revision (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  item_state_id BIGINT NOT NULL,
  source_version VARCHAR(160) NOT NULL,
  basis_cny NUMERIC(14,2),
  successful_refund_cny NUMERIC(14,2),
  rate NUMERIC(6,4),
  config_version INTEGER,
  rule_version_id BIGINT,
  accrued_cny NUMERIC(14,2) NOT NULL,
  delta_cny NUMERIC(14,2) NOT NULL,
  release_basis VARCHAR(24) NOT NULL,
  evidence VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_item_revision PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_item_revision_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_item_revision_basis CHECK (release_basis IN ('CONFIRMED', 'FALLBACK', 'UNSETTLED', 'AFTER_SALES', 'UNVERIFIED')),
  CONSTRAINT ck_crm_commission_item_revision_amounts CHECK (accrued_cny >= 0)
);
CREATE UNIQUE INDEX uk_crm_commission_item_revision_version ON crm_commission_item_revision (tenant_id, item_state_id, source_version);
CREATE INDEX idx_crm_commission_item_revision_item ON crm_commission_item_revision (tenant_id, item_state_id, create_time, id);
COMMENT ON TABLE crm_commission_item_revision IS '商品行计佣修订（只追加，任务 62 / ADR-032 §2）：来源版本变化保存基数、比例快照与钱包差额；无修改与删除';
COMMENT ON COLUMN crm_commission_item_revision.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_item_revision.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_item_revision.item_state_id IS 'crm_commission_item_state.id';
COMMENT ON COLUMN crm_commission_item_revision.source_version IS '本修订对应的来源快照版本指纹；同一商品行同一版本至多一条';
COMMENT ON COLUMN crm_commission_item_revision.basis_cny IS '本修订的有效金额基数快照；未核实为空';
COMMENT ON COLUMN crm_commission_item_revision.successful_refund_cny IS '本修订的成功退款快照；来源未提供为空';
COMMENT ON COLUMN crm_commission_item_revision.rate IS '本修订使用的佣金比例快照 NUMERIC(6,4)';
COMMENT ON COLUMN crm_commission_item_revision.config_version IS '比例来源的推广配置版本号；比例来自商品级或无配置时为空';
COMMENT ON COLUMN crm_commission_item_revision.rule_version_id IS '本修订快照的结算配置版本';
COMMENT ON COLUMN crm_commission_item_revision.accrued_cny IS '修订后的累计净计佣';
COMMENT ON COLUMN crm_commission_item_revision.delta_cny IS '本修订直接产生的带符号钱包差额（负向调整或归属回冲）；仅计提变化为 0';
COMMENT ON COLUMN crm_commission_item_revision.release_basis IS '本修订计算的释放依据';
COMMENT ON COLUMN crm_commission_item_revision.evidence IS '来源核验说明，如同步来源与核实结论，≤1000 字';
COMMENT ON COLUMN crm_commission_item_revision.create_time IS '修订时间 UTC';
