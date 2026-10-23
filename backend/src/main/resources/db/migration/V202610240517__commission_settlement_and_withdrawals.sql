-- Commission settlement and withdrawal workflow.  The wallet/ledger remains the single balance
-- authority created by V202610221900; these tables only retain source, allocation and workflow data.

ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_type;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_type
  CHECK (entry_type IN ('COMMISSION_CREDIT','COMMISSION_ADJUSTMENT','COMMISSION_WITHDRAWAL','PREPAY_DEDUCT'));
ALTER TABLE crm_commission_ledger DROP CONSTRAINT ck_crm_commission_ledger_amount;
ALTER TABLE crm_commission_ledger ADD CONSTRAINT ck_crm_commission_ledger_amount CHECK (
  (entry_type = 'COMMISSION_CREDIT' AND amount_cny > 0)
  OR (entry_type = 'COMMISSION_ADJUSTMENT' AND amount_cny <> 0)
  OR (entry_type IN ('COMMISSION_WITHDRAWAL','PREPAY_DEDUCT') AND amount_cny < 0)
);

CREATE TABLE crm_commission_rule_version (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  version_no BIGINT NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
  minimum_withdrawal_cny NUMERIC(14,2) NOT NULL DEFAULT 200.00,
  channel_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
  actor_id BIGINT NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_rule_version PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_rule_tenant_version UNIQUE (tenant_id, version_no),
  CONSTRAINT ck_crm_commission_rule_minimum CHECK (minimum_withdrawal_cny >= 0),
  CONSTRAINT ck_crm_commission_rule_currency CHECK (currency = 'CNY')
);
CREATE INDEX idx_crm_commission_rule_current ON crm_commission_rule_version (tenant_id, version_no DESC);
COMMENT ON TABLE crm_commission_rule_version IS '公司佣金冻结及最低提现配置历史；只追加，新规则仅用于之后支付订单';

CREATE TABLE crm_kol_commission_rate_version (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  rate NUMERIC(7,4) NOT NULL,
  effective_from TIMESTAMPTZ(3) NOT NULL,
  actor_id BIGINT,
  evidence VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_kol_commission_rate_version PRIMARY KEY (id),
  CONSTRAINT ck_crm_kol_commission_rate_range CHECK (rate >= 0 AND rate <= 1)
);
CREATE INDEX idx_crm_kol_commission_rate_at ON crm_kol_commission_rate_version (tenant_id, kol_id, effective_from DESC, id DESC);
COMMENT ON TABLE crm_kol_commission_rate_version IS '达人佣金比例生效历史；0.0000是有效零比例';

-- The legacy default_rate has no edit history. Preserve its current value only for orders paid
-- after this migration; never infer a historical rate for older orders.
INSERT INTO crm_kol_commission_rate_version (
  id, tenant_id, kol_id, rate, effective_from, actor_id, evidence, create_time
)
SELECT id, tenant_id, kol_id, (after_json->>'defaultRate')::numeric, effective_time,
       create_by, 'Historical promotion config version ' || version_no, create_time
FROM crm_kol_promotion_record
WHERE change_type='CONFIG' AND effective_time IS NOT NULL
  AND after_json->>'defaultRate' ~ '^[0-9]+(\.[0-9]{1,4})?$';

INSERT INTO crm_kol_commission_rate_version (
  id, tenant_id, kol_id, rate, effective_from, actor_id, evidence, create_time
)
SELECT k.id, k.tenant_id, k.id, k.default_rate, CURRENT_TIMESTAMP, NULL,
       'Legacy default_rate; effective from commission ledger launch only', CURRENT_TIMESTAMP
FROM crm_kol k
WHERE k.default_rate IS NOT NULL AND k.is_deleted=FALSE
  AND NOT EXISTS (SELECT 1 FROM crm_kol_commission_rate_version r WHERE r.tenant_id=k.tenant_id AND r.kol_id=k.id);

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
  basis_semantics VARCHAR(48),
  basis_includes_refund BOOLEAN,
  settlement_state VARCHAR(24) NOT NULL,
  settlement_time TIMESTAMPTZ(3),
  completed_time TIMESTAMPTZ(3),
  after_sales BOOLEAN NOT NULL DEFAULT FALSE,
  verified BOOLEAN NOT NULL DEFAULT FALSE,
  rate_version_id BIGINT,
  rule_version_id BIGINT,
  accrued_commission_cny NUMERIC(14,2) NOT NULL DEFAULT 0,
  released_commission_cny NUMERIC(14,2) NOT NULL DEFAULT 0,
  release_basis VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  release_at TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_item_state PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_item_state_item UNIQUE (tenant_id, order_item_id),
  CONSTRAINT uk_crm_commission_item_state_source UNIQUE (tenant_id, source_key),
  CONSTRAINT ck_crm_commission_item_state_settlement CHECK (settlement_state IN ('CONFIRMED','UNSETTLED','UNKNOWN')),
  CONSTRAINT ck_crm_commission_item_state_release CHECK (release_basis IN ('CONFIRMED','FALLBACK','UNSETTLED','AFTER_SALES','UNVERIFIED')),
  CONSTRAINT ck_crm_commission_item_state_basis CHECK (basis_cny IS NULL OR basis_cny >= 0),
  CONSTRAINT ck_crm_commission_item_state_refund CHECK (successful_refund_cny IS NULL OR successful_refund_cny >= 0),
  CONSTRAINT ck_crm_commission_item_state_commission CHECK (accrued_commission_cny >= 0 AND released_commission_cny >= 0)
);
CREATE INDEX idx_crm_commission_item_state_kol_release ON crm_commission_item_state (tenant_id, kol_id, release_at, order_item_id);
CREATE INDEX idx_crm_commission_item_state_order ON crm_commission_item_state (tenant_id, order_id, order_item_id);
COMMENT ON TABLE crm_commission_item_state IS '经验证的微店商品行计佣快照；来源不完整时明确待核实，不以订单实付代替';

CREATE TABLE crm_commission_item_revision (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  item_state_id BIGINT NOT NULL,
  source_version VARCHAR(160) NOT NULL,
  basis_cny NUMERIC(14,2),
  successful_refund_cny NUMERIC(14,2),
  rate NUMERIC(7,4),
  rate_version_id BIGINT,
  rule_version_id BIGINT,
  accrued_cny NUMERIC(14,2),
  delta_cny NUMERIC(14,2),
  release_basis VARCHAR(24) NOT NULL,
  evidence VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_item_revision PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_item_revision_source UNIQUE (tenant_id, item_state_id, source_version)
);
CREATE INDEX idx_crm_commission_item_revision_item ON crm_commission_item_revision (tenant_id, item_state_id, create_time, id);
COMMENT ON TABLE crm_commission_item_revision IS '商品行计佣基数、比例、来源版本与增量历史；只追加';

-- Existing order items enter the accounting workflow as explicitly unverified, never as zero commission.
INSERT INTO crm_commission_item_state (
  id, tenant_id, order_id, order_item_id, kol_id, source_key, source_version, basis_cny,
  basis_semantics, basis_includes_refund, settlement_state, settlement_time, completed_time,
  after_sales, verified, release_basis, accrued_commission_cny, released_commission_cny,
  create_time, update_time
)
SELECT oi.id, oi.tenant_id, o.id, oi.id, oi.kol_id, 'ORDER_ITEM:' || oi.id, NULL, NULL,
       NULL, NULL, 'UNKNOWN', NULL, NULL, FALSE, FALSE, 'UNVERIFIED', 0, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM crm_order_item oi
JOIN crm_order o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id
WHERE oi.is_deleted = FALSE
ON CONFLICT (tenant_id, order_item_id) DO NOTHING;

CREATE TABLE crm_commission_withdrawal (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  application_no VARCHAR(40) NOT NULL,
  request_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  previous_id BIGINT,
  amount_cny NUMERIC(14,2) NOT NULL,
  status VARCHAR(24) NOT NULL,
  paused BOOLEAN NOT NULL DEFAULT FALSE,
  pause_reason VARCHAR(500),
  account_id BIGINT,
  account_type VARCHAR(8) NOT NULL,
  account_cipher VARCHAR(512) NOT NULL,
  account_masked VARCHAR(160) NOT NULL,
  account_currency VARCHAR(8),
  account_network VARCHAR(8),
  account_holder VARCHAR(64),
  account_qr_file_id BIGINT,
  note VARCHAR(1000),
  review_note VARCHAR(1000),
  finance_note VARCHAR(1000),
  actor_id BIGINT NOT NULL,
  reviewer_id BIGINT,
  reviewed_at TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_withdrawal_no UNIQUE (tenant_id, application_no),
  CONSTRAINT uk_crm_commission_withdrawal_request UNIQUE (tenant_id, kol_id, request_id),
  CONSTRAINT ck_crm_commission_withdrawal_amount CHECK (amount_cny > 0),
  CONSTRAINT ck_crm_commission_withdrawal_status CHECK (status IN ('PENDING_REVIEW','PENDING_PAYMENT','REJECTED','WITHDRAWN','PAID')),
  CONSTRAINT ck_crm_commission_withdrawal_type CHECK (account_type IN ('PAYPAL','USDT'))
);
CREATE INDEX idx_crm_commission_withdrawal_list ON crm_commission_withdrawal (tenant_id, status, create_time DESC, id DESC);
CREATE INDEX idx_crm_commission_withdrawal_kol ON crm_commission_withdrawal (tenant_id, kol_id, create_time DESC, id DESC);
COMMENT ON TABLE crm_commission_withdrawal IS '仅佣金提现申请及不可变收款快照；不包含独立预付任务';

CREATE TABLE crm_commission_withdrawal_allocation (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  item_state_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  occupied_cny NUMERIC(14,2) NOT NULL,
  remaining_snapshot_cny NUMERIC(14,2) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_allocation PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_withdrawal_alloc_item UNIQUE (tenant_id, withdrawal_id, order_item_id),
  CONSTRAINT ck_crm_commission_withdrawal_alloc_amount CHECK (occupied_cny > 0 AND remaining_snapshot_cny >= 0)
);
CREATE INDEX idx_crm_commission_withdrawal_alloc_item ON crm_commission_withdrawal_allocation (tenant_id, item_state_id, withdrawal_id);
COMMENT ON TABLE crm_commission_withdrawal_allocation IS '提现占用与提交时商品行剩余金额快照';

CREATE TABLE crm_commission_withdrawal_payment (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  request_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  actual_amount NUMERIC(20,6) NOT NULL,
  actual_currency VARCHAR(8) NOT NULL,
  paid_at TIMESTAMPTZ(3) NOT NULL,
  method VARCHAR(64) NOT NULL,
  conversion_note VARCHAR(1000),
  finance_note VARCHAR(1000),
  voucher_file_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_payment PRIMARY KEY (id),
  CONSTRAINT uk_crm_commission_withdrawal_payment UNIQUE (tenant_id, withdrawal_id),
  CONSTRAINT uk_crm_commission_withdrawal_payment_request UNIQUE (tenant_id, request_id),
  CONSTRAINT ck_crm_commission_withdrawal_payment_amount CHECK (amount_cny > 0 AND actual_amount > 0)
);
COMMENT ON TABLE crm_commission_withdrawal_payment IS '线下佣金提现付款事实，一单最多登记一次，保存幂等摘要和凭证引用';

CREATE TABLE crm_commission_withdrawal_history (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  actor_id BIGINT,
  note VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_history PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_history_action CHECK (action IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN','PAID','PAUSED','RESUMED'))
);
CREATE INDEX idx_crm_commission_withdrawal_history ON crm_commission_withdrawal_history (tenant_id, withdrawal_id, create_time, id);
COMMENT ON TABLE crm_commission_withdrawal_history IS '佣金提现状态及退款阻断只追加历史';

COMMENT ON COLUMN crm_commission_rule_version.id IS '规则版本雪花主键';
COMMENT ON COLUMN crm_commission_rule_version.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_rule_version.version_no IS '公司内单调递增版本号';
COMMENT ON COLUMN crm_commission_rule_version.currency IS '账本币种，固定 CNY';
COMMENT ON COLUMN crm_commission_rule_version.minimum_withdrawal_cny IS '最低提现金额 CNY';
COMMENT ON COLUMN crm_commission_rule_version.channel_rules IS '渠道冻结天数等不可变规则快照';
COMMENT ON COLUMN crm_commission_rule_version.actor_id IS '规则修改人';
COMMENT ON COLUMN crm_commission_rule_version.create_time IS '规则生效版本创建时间 UTC';
COMMENT ON COLUMN crm_kol_commission_rate_version.id IS '比例版本雪花主键';
COMMENT ON COLUMN crm_kol_commission_rate_version.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_commission_rate_version.kol_id IS '佣金比例所属达人';
COMMENT ON COLUMN crm_kol_commission_rate_version.rate IS '订单支付时适用的比例，比值 0 至 1，四位小数';
COMMENT ON COLUMN crm_kol_commission_rate_version.effective_from IS '比例开始适用时间 UTC';
COMMENT ON COLUMN crm_kol_commission_rate_version.actor_id IS '比例设置人；上线回填当前默认比例时为空';
COMMENT ON COLUMN crm_kol_commission_rate_version.evidence IS '比例依据说明';
COMMENT ON COLUMN crm_kol_commission_rate_version.create_time IS '记录创建时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.id IS '商品行结算状态雪花主键';
COMMENT ON COLUMN crm_commission_item_state.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_item_state.order_id IS 'CRM 订单 ID';
COMMENT ON COLUMN crm_commission_item_state.order_item_id IS 'CRM 订单商品行 ID';
COMMENT ON COLUMN crm_commission_item_state.kol_id IS '经核实的达人归属；待归属为空';
COMMENT ON COLUMN crm_commission_item_state.source_key IS '微店商品行稳定来源键';
COMMENT ON COLUMN crm_commission_item_state.source_version IS '微店来源快照版本';
COMMENT ON COLUMN crm_commission_item_state.source_updated_at IS '来源快照更新时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.basis_cny IS '来源提供的佣金计费依据 CNY，不使用订单实付替代';
COMMENT ON COLUMN crm_commission_item_state.successful_refund_cny IS '来源确认的成功退款金额 CNY';
COMMENT ON COLUMN crm_commission_item_state.basis_semantics IS '计费依据字段语义；未知时不计佣';
COMMENT ON COLUMN crm_commission_item_state.basis_includes_refund IS '计费依据是否已含成功退款扣减';
COMMENT ON COLUMN crm_commission_item_state.settlement_state IS '外部结算状态标准值';
COMMENT ON COLUMN crm_commission_item_state.settlement_time IS '外部确认结算时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.completed_time IS '交易完成时间 UTC；缺失时不使用付款时间兜底';
COMMENT ON COLUMN crm_commission_item_state.after_sales IS '售后未核实标记；阻止释放及新提现占用';
COMMENT ON COLUMN crm_commission_item_state.verified IS '计费依据、归属和比例均已核实';
COMMENT ON COLUMN crm_commission_item_state.rate_version_id IS '该订单适用的达人比例版本';
COMMENT ON COLUMN crm_commission_item_state.rule_version_id IS '该订单适用的冻结规则版本';
COMMENT ON COLUMN crm_commission_item_state.accrued_commission_cny IS '商品行累计净计佣金额 CNY';
COMMENT ON COLUMN crm_commission_item_state.released_commission_cny IS '已释放到唯一佣金钱包的金额 CNY';
COMMENT ON COLUMN crm_commission_item_state.release_basis IS '释放依据标准状态';
COMMENT ON COLUMN crm_commission_item_state.release_at IS '预计或实际释放时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.create_time IS '记录创建时间 UTC';
COMMENT ON COLUMN crm_commission_item_state.update_time IS '记录更新时间 UTC';
COMMENT ON COLUMN crm_commission_item_revision.id IS '不可变计佣修订雪花主键';
COMMENT ON COLUMN crm_commission_item_revision.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_item_revision.item_state_id IS '对应商品行状态 ID';
COMMENT ON COLUMN crm_commission_item_revision.source_version IS '触发本次修订的来源版本';
COMMENT ON COLUMN crm_commission_item_revision.basis_cny IS '本次来源提供的原始计佣依据 CNY';
COMMENT ON COLUMN crm_commission_item_revision.successful_refund_cny IS '本次来源确认的成功退款 CNY';
COMMENT ON COLUMN crm_commission_item_revision.rate IS '本次计佣比例快照';
COMMENT ON COLUMN crm_commission_item_revision.rate_version_id IS '本次比例版本 ID';
COMMENT ON COLUMN crm_commission_item_revision.rule_version_id IS '本次冻结规则版本 ID';
COMMENT ON COLUMN crm_commission_item_revision.accrued_cny IS '本次修订后的净应计佣金';
COMMENT ON COLUMN crm_commission_item_revision.delta_cny IS '本次实际写入钱包的带符号差额';
COMMENT ON COLUMN crm_commission_item_revision.release_basis IS '本次修订对应的释放依据';
COMMENT ON COLUMN crm_commission_item_revision.evidence IS '外部来源核验说明';
COMMENT ON COLUMN crm_commission_item_revision.create_time IS '修订记录创建时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal.id IS '佣金提现申请雪花主键';
COMMENT ON COLUMN crm_commission_withdrawal.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_withdrawal.kol_id IS '申请达人';
COMMENT ON COLUMN crm_commission_withdrawal.application_no IS '公司内提现申请编号';
COMMENT ON COLUMN crm_commission_withdrawal.request_id IS '达人申请幂等 UUID';
COMMENT ON COLUMN crm_commission_withdrawal.request_fingerprint IS '申请内容摘要，防止同键异内容';
COMMENT ON COLUMN crm_commission_withdrawal.previous_id IS '被驳回后重提时关联的前一申请';
COMMENT ON COLUMN crm_commission_withdrawal.amount_cny IS '申请核销金额 CNY';
COMMENT ON COLUMN crm_commission_withdrawal.status IS '佣金提现工作流状态';
COMMENT ON COLUMN crm_commission_withdrawal.paused IS '退款或来源核验阻断标记';
COMMENT ON COLUMN crm_commission_withdrawal.pause_reason IS '暂停原因';
COMMENT ON COLUMN crm_commission_withdrawal.account_id IS '采用的本人已审核收款账户 ID；手填时为空';
COMMENT ON COLUMN crm_commission_withdrawal.account_type IS '收款方式 PAYPAL 或 USDT';
COMMENT ON COLUMN crm_commission_withdrawal.account_cipher IS '不可变收款账号加密快照';
COMMENT ON COLUMN crm_commission_withdrawal.account_masked IS '列表安全展示的脱敏账号';
COMMENT ON COLUMN crm_commission_withdrawal.account_currency IS 'PayPal 收款币种';
COMMENT ON COLUMN crm_commission_withdrawal.account_network IS 'USDT 网络';
COMMENT ON COLUMN crm_commission_withdrawal.account_holder IS '收款人姓名快照';
COMMENT ON COLUMN crm_commission_withdrawal.account_qr_file_id IS '申请时二维码附件 ID';
COMMENT ON COLUMN crm_commission_withdrawal.note IS '达人申请备注';
COMMENT ON COLUMN crm_commission_withdrawal.review_note IS '运营审核备注';
COMMENT ON COLUMN crm_commission_withdrawal.finance_note IS '财务付款备注，仅授权付款管理员可读';
COMMENT ON COLUMN crm_commission_withdrawal.actor_id IS '申请达人用户 ID';
COMMENT ON COLUMN crm_commission_withdrawal.reviewer_id IS '最近审核人用户 ID';
COMMENT ON COLUMN crm_commission_withdrawal.reviewed_at IS '最近审核时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal.create_time IS '申请时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal.update_time IS '最近状态更新时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.id IS '商品行占用雪花主键';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.withdrawal_id IS '佣金提现申请 ID';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.item_state_id IS '被占用商品行状态 ID';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.order_item_id IS '被占用 CRM 商品行 ID';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.occupied_cny IS '本申请占用商品行金额 CNY';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.remaining_snapshot_cny IS '本行分配后剩余可提现金额快照';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.create_time IS '占用记录创建时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal_payment.id IS '佣金提现付款事实雪花主键';
COMMENT ON COLUMN crm_commission_withdrawal_payment.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_withdrawal_payment.withdrawal_id IS '对应提现申请 ID';
COMMENT ON COLUMN crm_commission_withdrawal_payment.request_id IS '付款登记幂等 UUID';
COMMENT ON COLUMN crm_commission_withdrawal_payment.request_fingerprint IS '付款内容摘要，防止同键异内容';
COMMENT ON COLUMN crm_commission_withdrawal_payment.amount_cny IS '核销佣金金额 CNY';
COMMENT ON COLUMN crm_commission_withdrawal_payment.actual_amount IS '线下实际付款金额';
COMMENT ON COLUMN crm_commission_withdrawal_payment.actual_currency IS '线下实际付款币种';
COMMENT ON COLUMN crm_commission_withdrawal_payment.paid_at IS '实际付款时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal_payment.method IS '线下付款方式';
COMMENT ON COLUMN crm_commission_withdrawal_payment.conversion_note IS '非 CNY 付款的换算说明';
COMMENT ON COLUMN crm_commission_withdrawal_payment.finance_note IS '付款内部备注';
COMMENT ON COLUMN crm_commission_withdrawal_payment.voucher_file_id IS '已认领付款凭证文件 ID';
COMMENT ON COLUMN crm_commission_withdrawal_payment.operator_id IS '付款登记人用户 ID';
COMMENT ON COLUMN crm_commission_withdrawal_payment.create_time IS '付款事实记录时间 UTC';
COMMENT ON COLUMN crm_commission_withdrawal_history.id IS '状态历史雪花主键';
COMMENT ON COLUMN crm_commission_withdrawal_history.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_withdrawal_history.withdrawal_id IS '佣金提现申请 ID';
COMMENT ON COLUMN crm_commission_withdrawal_history.action IS '状态或阻断动作';
COMMENT ON COLUMN crm_commission_withdrawal_history.actor_id IS '动作用户；自动退款阻断为空';
COMMENT ON COLUMN crm_commission_withdrawal_history.note IS '动作说明；按角色裁剪展示';
COMMENT ON COLUMN crm_commission_withdrawal_history.create_time IS '动作发生时间 UTC';

-- Finance staff may reject a refund-blocked pending-payment request; final payment remains SUPER_ADMIN only.
LOCK TABLE sys_user_permission IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO sys_user_permission (id,tenant_id,user_id,permission_code,create_by,create_time)
SELECT (SELECT COALESCE(MAX(id),0) FROM sys_user_permission)
         + row_number() OVER (ORDER BY ut.tenant_id,u.id),
       ut.tenant_id,u.id,'withdrawal:review',u.id,CURRENT_TIMESTAMP
FROM sys_user u
JOIN sys_user_tenant ut ON ut.user_id=u.id
WHERE ut.role='STAFF' AND ut.post_template='FINANCE' AND ut.status=1 AND NOT ut.is_deleted
  AND NOT EXISTS(SELECT 1 FROM sys_user_permission p WHERE p.tenant_id=ut.tenant_id AND p.user_id=u.id AND p.permission_code='withdrawal:review');
