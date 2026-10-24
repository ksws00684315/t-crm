-- Task 47A: prepay applications, company/KOL commission wallets, immutable ledger and FIFO recovery.

CREATE TABLE crm_prepay (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  prepay_no VARCHAR(32) NOT NULL,
  request_id UUID NOT NULL,
  applicant_id BIGINT NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  reason VARCHAR(1000),
  expected_pay_date DATE,
  deduct_from_commission BOOLEAN,
  payout_account_id BIGINT,
  payout_account_type VARCHAR(8),
  payout_currency VARCHAR(8),
  payout_network VARCHAR(8),
  payout_holder_name VARCHAR(64),
  payout_account_cipher VARCHAR(512),
  payout_account_masked VARCHAR(160),
  status VARCHAR(24) NOT NULL,
  approved_by BIGINT,
  approved_time TIMESTAMPTZ(3),
  approval_reason VARCHAR(1000),
  cancelled_by BIGINT,
  cancelled_time TIMESTAMPTZ(3),
  cancel_reason VARCHAR(1000),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_prepay PRIMARY KEY (id),
  CONSTRAINT ck_crm_prepay_id CHECK (id > 0),
  CONSTRAINT ck_crm_prepay_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_prepay_amount CHECK (amount_cny > 0),
  CONSTRAINT ck_crm_prepay_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'REJECTED', 'CANCELLED')),
  CONSTRAINT ck_crm_prepay_account_type CHECK (payout_account_type IS NULL OR payout_account_type IN ('PAYPAL', 'USDT')),
  CONSTRAINT ck_crm_prepay_account_cipher CHECK (payout_account_cipher IS NULL OR payout_account_cipher LIKE 'v1:%'),
  CONSTRAINT ck_crm_prepay_approval CHECK ((status IN ('APPROVED', 'PAID') AND approved_by IS NOT NULL AND approved_time IS NOT NULL) OR (status NOT IN ('APPROVED', 'PAID') AND approved_by IS NULL AND approved_time IS NULL)),
  CONSTRAINT ck_crm_prepay_paid_data CHECK (status <> 'PAID' OR (payout_account_cipher IS NOT NULL AND deduct_from_commission IS NOT NULL))
);
CREATE UNIQUE INDEX uk_crm_prepay_tenant_no ON crm_prepay (tenant_id, prepay_no);
CREATE UNIQUE INDEX uk_crm_prepay_tenant_request ON crm_prepay (tenant_id, request_id);
CREATE INDEX idx_crm_prepay_tenant_status_time ON crm_prepay (tenant_id, status, create_time DESC, id DESC);
CREATE INDEX idx_crm_prepay_kol_status ON crm_prepay (kol_id, status, create_time DESC, id DESC);
COMMENT ON TABLE crm_prepay IS '达人预付申请及唯一财务任务聚合；财务补录后冻结收款快照；无第二份任务状态';
COMMENT ON COLUMN crm_prepay.id IS '正数雪花主键，同时作为 PREPAY 财务任务投影 ID';
COMMENT ON COLUMN crm_prepay.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_prepay.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_prepay.prepay_no IS '公司内唯一预付编号 PP+北京时间日期+六位序号';
COMMENT ON COLUMN crm_prepay.request_id IS '客户端申请 UUID 幂等键';
COMMENT ON COLUMN crm_prepay.applicant_id IS '申请时商务 sys_user.id，撤回时仍须核验当前达人归属';
COMMENT ON COLUMN crm_prepay.amount_cny IS '申请金额，人民币 CNY，两位小数';
COMMENT ON COLUMN crm_prepay.reason IS '财务待审核任务补录的申请原因；未补录时为空';
COMMENT ON COLUMN crm_prepay.expected_pay_date IS '财务待审核任务补录的期望付款日期；未补录时为空';
COMMENT ON COLUMN crm_prepay.deduct_from_commission IS '财务待审核任务明确选择是否抵扣佣金；空表示尚未补录';
COMMENT ON COLUMN crm_prepay.payout_account_id IS '补录时选中的已审核收款账户 ID；历史快照独立保存';
COMMENT ON COLUMN crm_prepay.payout_account_type IS '收款账户快照类型：PAYPAL 或 USDT';
COMMENT ON COLUMN crm_prepay.payout_currency IS '收款账户快照币种；仅 PayPal 有值';
COMMENT ON COLUMN crm_prepay.payout_network IS '收款账户快照网络；仅 USDT 有值';
COMMENT ON COLUMN crm_prepay.payout_holder_name IS '收款账户快照持有人；仅 PayPal 有值';
COMMENT ON COLUMN crm_prepay.payout_account_cipher IS '收款账户完整快照密文；仅受审计的 prepay:pay 接口可解密';
COMMENT ON COLUMN crm_prepay.payout_account_masked IS '普通详情使用的脱敏收款账号快照';
COMMENT ON COLUMN crm_prepay.status IS '状态：PENDING_APPROVAL 待审核、APPROVED 待支付、PAID 已支付、REJECTED 已驳回、CANCELLED 已撤回';
COMMENT ON COLUMN crm_prepay.approved_by IS '审核通过人 sys_user.id；仅 APPROVED/PAID 有值';
COMMENT ON COLUMN crm_prepay.approved_time IS '审核通过时间 UTC';
COMMENT ON COLUMN crm_prepay.approval_reason IS '驳回原因；仅驳回时有值';
COMMENT ON COLUMN crm_prepay.cancelled_by IS '撤回操作人 sys_user.id';
COMMENT ON COLUMN crm_prepay.cancelled_time IS '撤回时间 UTC';
COMMENT ON COLUMN crm_prepay.cancel_reason IS '撤回原因，可空';
COMMENT ON COLUMN crm_prepay.create_by IS '创建人 sys_user.id';
COMMENT ON COLUMN crm_prepay.create_time IS '申请时间 UTC';
COMMENT ON COLUMN crm_prepay.update_by IS '最近修改人 sys_user.id';
COMMENT ON COLUMN crm_prepay.update_time IS '最近修改时间 UTC';

CREATE TABLE crm_prepay_payment (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  prepay_id BIGINT NOT NULL,
  request_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  pay_amount_cny NUMERIC(14,2) NOT NULL,
  pay_time TIMESTAMPTZ(3) NOT NULL,
  pay_method VARCHAR(100) NOT NULL,
  pay_remark VARCHAR(1000),
  voucher_file_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_prepay_payment PRIMARY KEY (id),
  CONSTRAINT ck_crm_prepay_payment_id CHECK (id > 0),
  CONSTRAINT ck_crm_prepay_payment_amount CHECK (pay_amount_cny > 0),
  CONSTRAINT ck_crm_prepay_payment_method CHECK (length(btrim(pay_method)) > 0)
);
CREATE UNIQUE INDEX uk_crm_prepay_payment_prepay ON crm_prepay_payment (tenant_id, prepay_id);
CREATE UNIQUE INDEX uk_crm_prepay_payment_request ON crm_prepay_payment (tenant_id, request_id);
COMMENT ON TABLE crm_prepay_payment IS '线下已付款事实；唯一申请最多一笔支付；支付凭证引用 crm_file';
COMMENT ON COLUMN crm_prepay_payment.id IS '正数雪花主键';
COMMENT ON COLUMN crm_prepay_payment.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_prepay_payment.prepay_id IS '唯一对应 crm_prepay.id';
COMMENT ON COLUMN crm_prepay_payment.request_id IS '支付登记客户端 UUID 幂等键';
COMMENT ON COLUMN crm_prepay_payment.request_fingerprint IS '规范化支付字段及凭证内容 SHA-256，用于安全幂等重放';
COMMENT ON COLUMN crm_prepay_payment.pay_amount_cny IS '财务确认的人民币 CNY 支付金额';
COMMENT ON COLUMN crm_prepay_payment.pay_time IS '线下支付完成时间 UTC';
COMMENT ON COLUMN crm_prepay_payment.pay_method IS '财务填写的自由文本支付方式';
COMMENT ON COLUMN crm_prepay_payment.pay_remark IS '差额说明及可选备注；不得存入外币数量字段';
COMMENT ON COLUMN crm_prepay_payment.voucher_file_id IS '已鉴权的 PREPAY_VOUCHER 文件 ID';
COMMENT ON COLUMN crm_prepay_payment.operator_id IS '登记支付的财务 sys_user.id';
COMMENT ON COLUMN crm_prepay_payment.create_time IS '登记时间 UTC';

CREATE TABLE crm_prepay_history (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  prepay_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_prepay_history PRIMARY KEY (id),
  CONSTRAINT ck_crm_prepay_history_id CHECK (id > 0),
  CONSTRAINT ck_crm_prepay_history_action CHECK (action IN ('SUBMITTED', 'DETAILS_COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED', 'PAID'))
);
CREATE INDEX idx_crm_prepay_history_task ON crm_prepay_history (tenant_id, prepay_id, create_time, id);
COMMENT ON TABLE crm_prepay_history IS '预付申请、财务补录、审批、撤回和付款的只追加状态轨迹';
COMMENT ON COLUMN crm_prepay_history.id IS '正数雪花主键';
COMMENT ON COLUMN crm_prepay_history.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_prepay_history.prepay_id IS 'crm_prepay.id';
COMMENT ON COLUMN crm_prepay_history.action IS '动作：SUBMITTED 提交、DETAILS_COMPLETED 补录、APPROVED 通过、REJECTED 驳回、CANCELLED 撤回、PAID 登记付款';
COMMENT ON COLUMN crm_prepay_history.operator_id IS '操作人 sys_user.id';
COMMENT ON COLUMN crm_prepay_history.reason IS '驳回、撤回或补录摘要，可空；不保存完整收款账号';
COMMENT ON COLUMN crm_prepay_history.create_time IS '动作时间 UTC';

CREATE TABLE crm_commission_wallet (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  balance_cny NUMERIC(14,2) NOT NULL DEFAULT 0,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_wallet PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_wallet_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_wallet_balance CHECK (balance_cny <= 999999999999.99)
);
CREATE UNIQUE INDEX uk_crm_commission_wallet_kol ON crm_commission_wallet (tenant_id, kol_id);
COMMENT ON TABLE crm_commission_wallet IS '公司与达人隔离的可结算佣金余额汇总；余额允许负数以记录佣金抵扣预付';
COMMENT ON COLUMN crm_commission_wallet.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_wallet.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_wallet.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_commission_wallet.balance_cny IS '人民币可结算余额；负数为尚待佣金冲抵的预付';
COMMENT ON COLUMN crm_commission_wallet.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_commission_wallet.update_time IS '最近记账时间 UTC';

CREATE TABLE crm_commission_ledger (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  entry_type VARCHAR(32) NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id BIGINT NOT NULL,
  source_key VARCHAR(120) NOT NULL,
  occurred_at TIMESTAMPTZ(3) NOT NULL,
  balance_after_cny NUMERIC(14,2) NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_ledger PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_ledger_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_ledger_type CHECK (entry_type IN ('COMMISSION_CREDIT', 'PREPAY_DEDUCT')),
  CONSTRAINT ck_crm_commission_ledger_amount CHECK ((entry_type = 'COMMISSION_CREDIT' AND amount_cny > 0) OR (entry_type = 'PREPAY_DEDUCT' AND amount_cny < 0))
);
CREATE UNIQUE INDEX uk_crm_commission_ledger_source ON crm_commission_ledger (tenant_id, source_key);
CREATE INDEX idx_crm_commission_ledger_kol_time ON crm_commission_ledger (tenant_id, kol_id, occurred_at, id);
COMMENT ON TABLE crm_commission_ledger IS '不可变佣金钱包台账；正向可结算入账及预付抵扣；余额汇总可由事件核对';
COMMENT ON COLUMN crm_commission_ledger.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_ledger.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_ledger.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_commission_ledger.entry_type IS '账目类型：COMMISSION_CREDIT 可结算佣金入账、PREPAY_DEDUCT 预付抵扣';
COMMENT ON COLUMN crm_commission_ledger.amount_cny IS '带符号人民币金额；收入为正、抵扣为负';
COMMENT ON COLUMN crm_commission_ledger.source_type IS '业务来源类型';
COMMENT ON COLUMN crm_commission_ledger.source_id IS '业务来源主键';
COMMENT ON COLUMN crm_commission_ledger.source_key IS '公司内记账来源幂等键';
COMMENT ON COLUMN crm_commission_ledger.occurred_at IS '业务记账发生时间 UTC';
COMMENT ON COLUMN crm_commission_ledger.balance_after_cny IS '本笔记账完成后的钱包余额';
COMMENT ON COLUMN crm_commission_ledger.create_by IS '操作人 sys_user.id；自动佣金为空';
COMMENT ON COLUMN crm_commission_ledger.create_time IS '写入时间 UTC';

CREATE TABLE crm_commission_recovery (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  credit_ledger_id BIGINT NOT NULL,
  deduction_ledger_id BIGINT NOT NULL,
  amount_cny NUMERIC(14,2) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_recovery PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_recovery_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_recovery_amount CHECK (amount_cny > 0)
);
CREATE UNIQUE INDEX uk_crm_commission_recovery_pair ON crm_commission_recovery (tenant_id, credit_ledger_id, deduction_ledger_id);
CREATE INDEX idx_crm_commission_recovery_fifo ON crm_commission_recovery (tenant_id, kol_id, deduction_ledger_id, credit_ledger_id);
COMMENT ON TABLE crm_commission_recovery IS '可结算佣金正向入账按时间及 ID FIFO 分配给预付负向台账的持久化冲抵记录';
COMMENT ON COLUMN crm_commission_recovery.id IS '正数雪花主键';
COMMENT ON COLUMN crm_commission_recovery.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_commission_recovery.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_commission_recovery.credit_ledger_id IS '本次正向佣金台账 ID';
COMMENT ON COLUMN crm_commission_recovery.deduction_ledger_id IS '被冲抵的负向预付台账 ID';
COMMENT ON COLUMN crm_commission_recovery.amount_cny IS '本次分配冲抵金额 CNY';
COMMENT ON COLUMN crm_commission_recovery.create_time IS '分配时间 UTC';

CREATE TABLE crm_prepay_sequence (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  sequence_date DATE NOT NULL,
  last_value INTEGER NOT NULL,
  CONSTRAINT pk_crm_prepay_sequence PRIMARY KEY (id),
  CONSTRAINT uk_crm_prepay_sequence_date UNIQUE (tenant_id, sequence_date),
  CONSTRAINT ck_crm_prepay_sequence_value CHECK (last_value BETWEEN 1 AND 999999)
);
COMMENT ON TABLE crm_prepay_sequence IS '预付编号按公司及北京时间日期分配的六位序号';
COMMENT ON COLUMN crm_prepay_sequence.id IS '正数雪花主键';
COMMENT ON COLUMN crm_prepay_sequence.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_prepay_sequence.sequence_date IS '编号日期，Asia/Shanghai 时区';
COMMENT ON COLUMN crm_prepay_sequence.last_value IS '当日最近分配序号';

CREATE TABLE crm_prepay_outbox (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  prepay_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload JSONB NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ(3) NOT NULL,
  delivered_at TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_prepay_outbox PRIMARY KEY (id),
  CONSTRAINT ck_crm_prepay_outbox_id CHECK (id > 0),
  CONSTRAINT ck_crm_prepay_outbox_type CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'PAID'))
);
CREATE UNIQUE INDEX uk_crm_prepay_outbox_event ON crm_prepay_outbox (tenant_id, prepay_id, event_type);
CREATE INDEX idx_crm_prepay_outbox_pending ON crm_prepay_outbox (next_attempt_at, id) WHERE delivered_at IS NULL;
COMMENT ON TABLE crm_prepay_outbox IS '与预付事务原子写入、可重试的站内通知事件';
COMMENT ON COLUMN crm_prepay_outbox.id IS '正数雪花主键';
COMMENT ON COLUMN crm_prepay_outbox.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_prepay_outbox.prepay_id IS 'crm_prepay.id';
COMMENT ON COLUMN crm_prepay_outbox.event_type IS '通知事件：提交、通过、驳回、支付完成';
COMMENT ON COLUMN crm_prepay_outbox.payload IS '仅包含预付编号及最小必要通知参数的 JSONB';
COMMENT ON COLUMN crm_prepay_outbox.attempts IS '通知投递尝试次数';
COMMENT ON COLUMN crm_prepay_outbox.next_attempt_at IS '下次可重试时间 UTC';
COMMENT ON COLUMN crm_prepay_outbox.delivered_at IS '通知成功写入时间 UTC；空表示待重试';
COMMENT ON COLUMN crm_prepay_outbox.create_time IS '事件写入时间 UTC';

ALTER TABLE crm_file DROP CONSTRAINT ck_crm_file_size;
ALTER TABLE crm_file ADD CONSTRAINT ck_crm_file_size CHECK (
  size_bytes > 0 AND
  ((biz_type IN ('PAYOUT_QR', 'WITHDRAWAL_VOUCHER') AND size_bytes <= 2097152) OR
   (biz_type IN ('PREPAY_ATTACHMENT', 'PREPAY_VOUCHER') AND size_bytes <= 10485760))
);
COMMENT ON COLUMN crm_file.size_bytes IS '文件字节数；收款二维码和提现凭证≤2MB，预付附件和付款凭证≤10MB';
COMMENT ON COLUMN crm_file.content_type IS '按文件头识别的实际类型：PNG/JPEG；预付附件和凭证也允许 PDF';

ALTER TABLE crm_notification DROP CONSTRAINT ck_crm_notification_type;
ALTER TABLE crm_notification ADD CONSTRAINT ck_crm_notification_type CHECK (
  type IN ('PAYOUT_ACCOUNT_SUBMITTED', 'ORDER_SYNC_FAILED', 'BD_TRANSFER_CANCELLED', 'KOLS_UNASSIGNED',
           'PREPAY_SUBMITTED', 'PREPAY_APPROVED', 'PREPAY_REJECTED', 'PREPAY_PAID',
           'DEPARTURE_PLAN_SUBMITTED', 'DEPARTURE_PLAN_EXECUTED')
);
COMMENT ON COLUMN crm_notification.type IS '消息类型：收款账户提交、订单同步失败、预约转移取消、达人转为未分配、预付提交、通过、驳回、支付完成';
CREATE UNIQUE INDEX uk_crm_notification_prepay_event
  ON crm_notification (tenant_id, user_id, biz_type, biz_id, type)
  WHERE biz_type = 'PREPAY';

-- Do not copy prepay:review onto members who already exist. Design §3.1: a company edit to a
-- post template applies only to accounts created afterwards. Payment rights stay explicit too.
