-- Commission withdrawal workflow (task 63, rows 12.01/12.02/13.01-13.04). The wallet and ledger
-- of V202610221900 stay the single balance authority: a payment posts one negative
-- COMMISSION_WITHDRAWAL ledger entry and lowers the same wallet row; these tables keep the
-- application, its FIFO order-item allocations, history, payment facts, notification outbox and
-- the creator freeze. No second wallet or balance table is created.

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
  account_cipher TEXT NOT NULL,
  account_masked VARCHAR(160) NOT NULL,
  account_currency VARCHAR(8),
  account_network VARCHAR(16),
  account_holder VARCHAR(64) NOT NULL,
  account_qr_file_id BIGINT,
  note VARCHAR(1000),
  review_note VARCHAR(1000),
  finance_note VARCHAR(1000),
  reviewer_id BIGINT,
  reviewed_at TIMESTAMPTZ(3),
  actor_id BIGINT NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_withdrawal_pause CHECK (
    (paused = FALSE AND pause_reason IS NULL) OR (paused = TRUE)),
  CONSTRAINT uk_crm_commission_withdrawal_no UNIQUE (tenant_id, application_no),
  CONSTRAINT uk_crm_commission_withdrawal_request UNIQUE (tenant_id, kol_id, request_id),
  CONSTRAINT ck_crm_commission_withdrawal_amount CHECK (amount_cny > 0),
  CONSTRAINT ck_crm_commission_withdrawal_status CHECK (
    status IN ('PENDING_REVIEW', 'PENDING_PAYMENT', 'REJECTED', 'WITHDRAWN', 'PAID')),
  CONSTRAINT ck_crm_commission_withdrawal_account_type CHECK (account_type IN ('PAYPAL', 'USDT')),
  CONSTRAINT ck_crm_commission_withdrawal_cipher CHECK (account_cipher LIKE 'v1:%'),
  CONSTRAINT ck_crm_commission_withdrawal_currency CHECK (
    account_type = 'USDT' OR account_currency IS NOT NULL)
);

COMMENT ON TABLE crm_commission_withdrawal IS
  '佣金提现申请：达人每次提交时快照并加密收款信息；一个 UUID 只能成功一笔；驳回后重新提交生成新单并引用 previous_id';
COMMENT ON COLUMN crm_commission_withdrawal.kol_id IS '提现达人 crm_kol.id';
COMMENT ON COLUMN crm_commission_withdrawal.application_no IS '申请编号：W+北京时间日期yyyyMMdd+六位序号（ADR-032 第 12 条），公司内唯一';
COMMENT ON COLUMN crm_commission_withdrawal.request_id IS '达人端幂等 UUID，公司内 (kol_id, request_id) 唯一';
COMMENT ON COLUMN crm_commission_withdrawal.request_fingerprint IS 'SHA-256(金额|previousId|收款|备注)，同 UUID 不同内容返回 409';
COMMENT ON COLUMN crm_commission_withdrawal.previous_id IS '驳回后重新提交时引用的原申请 id；首单为空';
COMMENT ON COLUMN crm_commission_withdrawal.amount_cny IS '申请金额（人民币）';
COMMENT ON COLUMN crm_commission_withdrawal.status IS
  '状态：PENDING_REVIEW 待审核、PENDING_PAYMENT 待支付、REJECTED 已驳回、WITHDRAWN 已撤回、PAID 已支付';
COMMENT ON COLUMN crm_commission_withdrawal.paused IS '退款或来源核验阻断标记（任务 62 写入）；阻断期间不能付款';
COMMENT ON COLUMN crm_commission_withdrawal.pause_reason IS '阻断原因；未阻断为空';
COMMENT ON COLUMN crm_commission_withdrawal.account_id IS '引用的已审核收款账户 crm_payout_account.id；每次手填时为空';
COMMENT ON COLUMN crm_commission_withdrawal.account_type IS '收款方式：PAYPAL 或 USDT';
COMMENT ON COLUMN crm_commission_withdrawal.account_cipher IS '收款账号（邮箱或钱包地址）密文，v1: 前缀 AES-256-GCM';
COMMENT ON COLUMN crm_commission_withdrawal.account_masked IS '脱敏收款账号，列表与导出只显示此列';
COMMENT ON COLUMN crm_commission_withdrawal.account_currency IS 'PayPal 收款币种（ISO 4217）；USDT 为空';
COMMENT ON COLUMN crm_commission_withdrawal.account_network IS 'USDT 网络：TRC20/ERC20/BEP20/SOL/TON；PayPal 为空';
COMMENT ON COLUMN crm_commission_withdrawal.account_holder IS '收款人姓名快照';
COMMENT ON COLUMN crm_commission_withdrawal.account_qr_file_id IS 'USDT 收款二维码 crm_file.id（申请时认领）；无则空';
COMMENT ON COLUMN crm_commission_withdrawal.note IS '达人申请备注';
COMMENT ON COLUMN crm_commission_withdrawal.review_note IS '审核备注；驳回时必填';
COMMENT ON COLUMN crm_commission_withdrawal.finance_note IS '财务备注；付款登记时填写，仅付款权限可见';
COMMENT ON COLUMN crm_commission_withdrawal.reviewer_id IS '最近一次审核人 sys_user.id';
COMMENT ON COLUMN crm_commission_withdrawal.reviewed_at IS '最近一次审核时间';
COMMENT ON COLUMN crm_commission_withdrawal.actor_id IS '提交人（达人 crm_kol.id）';

CREATE INDEX idx_crm_commission_withdrawal_list
  ON crm_commission_withdrawal (tenant_id, status, create_time DESC, id DESC);
CREATE INDEX idx_crm_commission_withdrawal_kol
  ON crm_commission_withdrawal (tenant_id, kol_id, create_time DESC, id DESC);

CREATE TABLE crm_commission_withdrawal_sequence (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  sequence_date DATE NOT NULL,
  last_value INTEGER NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_sequence PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_sequence_id CHECK (id > 0),
  CONSTRAINT uk_crm_commission_withdrawal_sequence UNIQUE (tenant_id, sequence_date),
  CONSTRAINT ck_crm_commission_withdrawal_sequence_value CHECK (last_value BETWEEN 1 AND 999999)
);

COMMENT ON TABLE crm_commission_withdrawal_sequence IS
  '提现编号序列：按公司、按北京时间日期分配六位序号；不是全局表';
COMMENT ON COLUMN crm_commission_withdrawal_sequence.sequence_date IS '北京时间日期';
COMMENT ON COLUMN crm_commission_withdrawal_sequence.last_value IS '当日已分配的最大序号 1~999999';

CREATE TABLE crm_commission_withdrawal_allocation (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  item_state_id BIGINT,
  order_no VARCHAR(32) NOT NULL,
  item_name VARCHAR(255) NOT NULL,
  sku_name VARCHAR(255),
  image_url VARCHAR(512),
  occupied_cny NUMERIC(14,2) NOT NULL,
  remaining_snapshot_cny NUMERIC(14,2) NOT NULL,
  source_released_cny NUMERIC(14,2) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_allocation PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_allocation_id CHECK (id > 0),
  CONSTRAINT uk_crm_commission_withdrawal_allocation UNIQUE (tenant_id, withdrawal_id, order_item_id),
  CONSTRAINT ck_crm_commission_withdrawal_allocation_occupied CHECK (occupied_cny > 0),
  CONSTRAINT ck_crm_commission_withdrawal_allocation_remaining CHECK (remaining_snapshot_cny >= 0),
  CONSTRAINT ck_crm_commission_withdrawal_allocation_source CHECK (source_released_cny >= 0)
);

COMMENT ON TABLE crm_commission_withdrawal_allocation IS
  '提现按先进先出分配到订单商品行：每行记录占用金额与分配后剩余快照；分配合计恒等于申请金额';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.order_item_id IS '订单商品行 crm_order_item.id';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.item_state_id IS '对应任务 62 的商品行计佣状态 crm_commission_item_state.id；建表时为空，任务 62 合并后回填，供退款阻断反查';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.order_no IS '订单号快照（下单时保存）';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.item_name IS '商品名称快照';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.sku_name IS '型号名称快照；可空';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.image_url IS '商品主图快照；可空';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.occupied_cny IS '本申请占用该商品行的金额';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.remaining_snapshot_cny IS
  '分配后该商品行剩余可提现金额快照（原始佣金减去含本行在内全部占用）';
COMMENT ON COLUMN crm_commission_withdrawal_allocation.source_released_cny IS
  '分配时该商品行已释放佣金（原始佣金）快照';

CREATE INDEX idx_crm_commission_withdrawal_allocation_item
  ON crm_commission_withdrawal_allocation (tenant_id, order_item_id, withdrawal_id);
CREATE INDEX idx_crm_commission_withdrawal_allocation_state
  ON crm_commission_withdrawal_allocation (tenant_id, item_state_id, withdrawal_id)
  WHERE item_state_id IS NOT NULL;

CREATE TABLE crm_commission_withdrawal_order (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  occupied_cny NUMERIC(14,2) NOT NULL,
  remaining_snapshot_cny NUMERIC(14,2),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_order PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_order_id CHECK (id > 0),
  CONSTRAINT uk_crm_commission_withdrawal_order UNIQUE (tenant_id, withdrawal_id, order_id),
  CONSTRAINT ck_crm_commission_withdrawal_order_occupied CHECK (occupied_cny > 0),
  CONSTRAINT ck_crm_commission_withdrawal_order_remaining CHECK (remaining_snapshot_cny IS NULL OR remaining_snapshot_cny >= 0)
);

COMMENT ON TABLE crm_commission_withdrawal_order IS
  '提现订单级占用快照（申请时固化，之后不改写）；关联订单分页按本表每单一行';
COMMENT ON COLUMN crm_commission_withdrawal_order.order_id IS '订单 crm_order.id';
COMMENT ON COLUMN crm_commission_withdrawal_order.order_no IS '订单号快照（下单时保存）';
COMMENT ON COLUMN crm_commission_withdrawal_order.occupied_cny IS '该订单被本申请占用的合计金额';
COMMENT ON COLUMN crm_commission_withdrawal_order.remaining_snapshot_cny IS
  '申请时刻该订单分配后剩余可提现金额的历史值；缺失为 NULL 由前端显示待核实，禁止以当前钱包或行级求和代替';

CREATE INDEX idx_crm_commission_withdrawal_order_page
  ON crm_commission_withdrawal_order (tenant_id, withdrawal_id, id);

CREATE TABLE crm_commission_withdrawal_history (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  actor_id BIGINT,
  actor_name VARCHAR(100),
  actor_role VARCHAR(32) NOT NULL,
  note VARCHAR(1000),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_history PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_history_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_withdrawal_history_action CHECK (
    action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'PAID', 'PAUSED', 'RESUMED')),
  CONSTRAINT ck_crm_commission_withdrawal_history_role CHECK (
    actor_role IN ('KOL', 'REVIEW', 'PAYMENT', 'SYSTEM'))
);

COMMENT ON TABLE crm_commission_withdrawal_history IS
  '提现操作历史（只追加）：商务转交后已完成的审批保留原操作人快照，不随达人改归属';
COMMENT ON COLUMN crm_commission_withdrawal_history.action IS
  '动作：SUBMITTED 提交、APPROVED 审核通过、REJECTED 驳回、WITHDRAWN 撤回、PAID 付款登记、PAUSED/RESUMED 阻断与解除（任务 62 写入）';
COMMENT ON COLUMN crm_commission_withdrawal_history.actor_id IS '操作人：达人为 crm_kol.id，管理端为 sys_user.id';
COMMENT ON COLUMN crm_commission_withdrawal_history.actor_name IS '操作人姓名快照';
COMMENT ON COLUMN crm_commission_withdrawal_history.actor_role IS '操作方：KOL 达人、REVIEW 审核、PAYMENT 付款、SYSTEM 系统';

CREATE INDEX idx_crm_commission_withdrawal_history
  ON crm_commission_withdrawal_history (tenant_id, withdrawal_id, create_time, id);

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
  method VARCHAR(100) NOT NULL,
  finance_note VARCHAR(1000),
  conversion_note VARCHAR(1000),
  voucher_file_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_name VARCHAR(100),
  operator_role VARCHAR(32) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_payment PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_payment_id CHECK (id > 0),
  CONSTRAINT uk_crm_commission_withdrawal_payment_task UNIQUE (tenant_id, withdrawal_id),
  CONSTRAINT uk_crm_commission_withdrawal_payment_request UNIQUE (tenant_id, request_id),
  CONSTRAINT ck_crm_commission_withdrawal_payment_amount CHECK (amount_cny > 0),
  CONSTRAINT ck_crm_commission_withdrawal_payment_actual CHECK (actual_amount > 0 AND actual_amount <= 999999999999.999999)
);

COMMENT ON TABLE crm_commission_withdrawal_payment IS
  '提现付款事实：一个申请至多一笔成功支付；登记后写佣金钱包台账并更新占用与已付金额';
COMMENT ON COLUMN crm_commission_withdrawal_payment.request_id IS '付款登记幂等 UUID';
COMMENT ON COLUMN crm_commission_withdrawal_payment.request_fingerprint IS 'SHA-256(申请|请求|金额|币种|时间|方式|备注|凭证)，重放比对';
COMMENT ON COLUMN crm_commission_withdrawal_payment.amount_cny IS '结算金额（人民币），与申请金额一致';
COMMENT ON COLUMN crm_commission_withdrawal_payment.actual_amount IS '实际支付金额（与 actual_currency 对应；非 CNY 按来源币种保留最多 6 位小数）';
COMMENT ON COLUMN crm_commission_withdrawal_payment.actual_currency IS '实际支付币种；非 CNY 时必须填写折算说明';
COMMENT ON COLUMN crm_commission_withdrawal_payment.paid_at IS '支付时间（UTC）';
COMMENT ON COLUMN crm_commission_withdrawal_payment.method IS '支付方式（线下渠道描述）';
COMMENT ON COLUMN crm_commission_withdrawal_payment.finance_note IS '财务备注；实际金额与结算金额不一致时必填';
COMMENT ON COLUMN crm_commission_withdrawal_payment.conversion_note IS '非 CNY 支付时的折算说明';
COMMENT ON COLUMN crm_commission_withdrawal_payment.voucher_file_id IS '支付凭证 crm_file.id（WITHDRAWAL_VOUCHER）';
COMMENT ON COLUMN crm_commission_withdrawal_payment.operator_id IS '登记人 sys_user.id';
COMMENT ON COLUMN crm_commission_withdrawal_payment.operator_name IS '登记人姓名快照';
COMMENT ON COLUMN crm_commission_withdrawal_payment.operator_role IS '操作方，固定 PAYMENT';

CREATE TABLE crm_commission_withdrawal_outbox (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  withdrawal_id BIGINT NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  payload JSONB NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ(3) NOT NULL,
  delivered_at TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_outbox PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_outbox_id CHECK (id > 0),
  CONSTRAINT uk_crm_commission_withdrawal_outbox UNIQUE (tenant_id, withdrawal_id, event_type),
  CONSTRAINT ck_crm_commission_withdrawal_outbox_event CHECK (
    event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'PAID'))
);

COMMENT ON TABLE crm_commission_withdrawal_outbox IS
  '提现通知发件箱：与申请状态同一事务写入，后台工人逐条投递给站内消息，可重试';
COMMENT ON COLUMN crm_commission_withdrawal_outbox.event_type IS '事件：SUBMITTED/APPROVED/REJECTED/PAID';
COMMENT ON COLUMN crm_commission_withdrawal_outbox.payload IS '事件参数 JSON，只含申请编号与达人编号';

CREATE INDEX idx_crm_commission_withdrawal_outbox_due
  ON crm_commission_withdrawal_outbox (next_attempt_at, id)
  WHERE delivered_at IS NULL;

CREATE TABLE crm_commission_withdrawal_freeze (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  frozen_by BIGINT NOT NULL,
  frozen_time TIMESTAMPTZ(3) NOT NULL,
  resume_reason VARCHAR(500),
  resumed_by BIGINT,
  resumed_time TIMESTAMPTZ(3),
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_commission_withdrawal_freeze PRIMARY KEY (id),
  CONSTRAINT ck_crm_commission_withdrawal_freeze_id CHECK (id > 0),
  CONSTRAINT ck_crm_commission_withdrawal_freeze_resumed CHECK (
    (resumed_by IS NULL AND resumed_time IS NULL) OR (resumed_by IS NOT NULL AND resumed_time IS NOT NULL))
);

COMMENT ON TABLE crm_commission_withdrawal_freeze IS
  '达人提现冻结（commission:freeze）：冻结期间不能提交新申请，已提交的申请不受影响；恢复后可再次申请，历史保留';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.kol_id IS '被冻结的达人 crm_kol.id';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.reason IS '冻结原因（必填）';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.frozen_by IS '冻结操作人 sys_user.id';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.frozen_time IS '冻结时间';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.resume_reason IS '恢复原因；可空';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.resumed_by IS '恢复操作人；未恢复为空';
COMMENT ON COLUMN crm_commission_withdrawal_freeze.resumed_time IS '恢复时间；未恢复为空';

CREATE UNIQUE INDEX uk_crm_commission_withdrawal_freeze_active
  ON crm_commission_withdrawal_freeze (tenant_id, kol_id)
  WHERE resumed_time IS NULL;

CREATE INDEX idx_crm_commission_withdrawal_freeze_kol
  ON crm_commission_withdrawal_freeze (tenant_id, kol_id, frozen_time DESC, id DESC);

-- Task 63 / ADR-032: the payment voucher is uploaded before registration and may be a PDF like the
-- prepay vouchers, so WITHDRAWAL_VOUCHER joins the 10MB group in the shared file-size rule.
ALTER TABLE crm_file DROP CONSTRAINT ck_crm_file_size;
ALTER TABLE crm_file ADD CONSTRAINT ck_crm_file_size CHECK (
  size_bytes > 0 AND
  ((biz_type = 'PAYOUT_QR' AND size_bytes <= 2097152) OR
   (biz_type IN ('WITHDRAWAL_VOUCHER', 'PREPAY_ATTACHMENT', 'PREPAY_VOUCHER')
      AND size_bytes <= 10485760))
);
COMMENT ON COLUMN crm_file.size_bytes IS '文件字节数；收款二维码≤2MB，预付附件/凭证与提现凭证≤10MB';
