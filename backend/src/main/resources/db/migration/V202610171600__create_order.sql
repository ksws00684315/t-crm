CREATE TABLE crm_order (
  id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  weidian_order_id VARCHAR(32) NOT NULL,
  kol_id BIGINT,
  bd_user_id BIGINT,
  settled_time TIMESTAMPTZ(3),
  attribution_status VARCHAR(32) NOT NULL,
  order_status VARCHAR(32) NOT NULL,
  raw_status VARCHAR(32) NOT NULL,
  buyer_hash CHAR(64),
  order_time TIMESTAMPTZ(3) NOT NULL,
  pay_time TIMESTAMPTZ(3),
  receive_time TIMESTAMPTZ(3),
  close_time TIMESTAMPTZ(3),
  total_amount NUMERIC(14,2) NOT NULL,
  pay_amount NUMERIC(14,2) NOT NULL,
  freight NUMERIC(14,2) NOT NULL,
  discount NUMERIC(14,2) NOT NULL,
  source INTEGER NOT NULL,
  import_batch_id BIGINT,
  last_sync_time TIMESTAMPTZ(3) NOT NULL,
  source_update_time TIMESTAMPTZ(3),
  tenant_id BIGINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_order PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_id CHECK (id > 0),
  CONSTRAINT uk_crm_order_identity UNIQUE (shop_id, weidian_order_id),
  CONSTRAINT ck_crm_order_status CHECK (order_status IN ('UNPAID','PAID','SHIPPED','RECEIVED','COMPLETED','CLOSED','UNKNOWN')),
  CONSTRAINT ck_crm_order_attribution CHECK (attribution_status IN ('PENDING','ATTRIBUTED','NON_KOL','CONFLICT','UNMATCHED'))
);
COMMENT ON TABLE crm_order IS '微店订单；公司隔离，雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_order.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order.shop_id IS '所属店铺';
COMMENT ON COLUMN crm_order.weidian_order_id IS '微店订单号';
COMMENT ON COLUMN crm_order.kol_id IS '所属达人；未匹配或冲突时可空';
COMMENT ON COLUMN crm_order.bd_user_id IS '支付时所属商务快照；空归公司';
COMMENT ON COLUMN crm_order.settled_time IS '微店结算时间；本任务不写入';
COMMENT ON COLUMN crm_order.attribution_status IS '归因状态：PENDING、ATTRIBUTED、NON_KOL、CONFLICT、UNMATCHED';
COMMENT ON COLUMN crm_order.order_status IS '标准状态：UNPAID、PAID、SHIPPED、RECEIVED、COMPLETED、CLOSED、UNKNOWN';
COMMENT ON COLUMN crm_order.raw_status IS '微店原始状态';
COMMENT ON COLUMN crm_order.buyer_hash IS '买家 ID 加盐 SHA-256；无 ID 时为空';
COMMENT ON COLUMN crm_order.order_time IS '下单时间 UTC';
COMMENT ON COLUMN crm_order.pay_time IS '支付时间 UTC；未支付为空';
COMMENT ON COLUMN crm_order.receive_time IS '确认收货时间；接口未提供时为空';
COMMENT ON COLUMN crm_order.close_time IS '关闭时间；接口未提供时为空';
COMMENT ON COLUMN crm_order.total_amount IS '订单总额，元';
COMMENT ON COLUMN crm_order.pay_amount IS '订单实付，元';
COMMENT ON COLUMN crm_order.freight IS '运费，元';
COMMENT ON COLUMN crm_order.discount IS '优惠，元；未提供时为零';
COMMENT ON COLUMN crm_order.source IS '来源：1 接口，2 导入';
COMMENT ON COLUMN crm_order.import_batch_id IS '导入批次；接口同步为空';
COMMENT ON COLUMN crm_order.last_sync_time IS '最近同步时间 UTC';
COMMENT ON COLUMN crm_order.source_update_time IS '最近已处理的微店更新时间；无更新时间时为空';
COMMENT ON COLUMN crm_order.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_order.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_order.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_order.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_order.update_time IS '修改时间 UTC';
COMMENT ON COLUMN crm_order.is_deleted IS '逻辑删除';
CREATE INDEX idx_crm_order_tenant_pay ON crm_order (tenant_id, pay_time);
CREATE INDEX idx_crm_order_kol_pay ON crm_order (kol_id, pay_time);
CREATE INDEX idx_crm_order_bd_pay ON crm_order (bd_user_id, pay_time);
CREATE INDEX idx_crm_order_tenant_attribution ON crm_order (tenant_id, attribution_status);
CREATE TABLE crm_order_item (
  id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  sub_order_id VARCHAR(128) NOT NULL,
  weidian_item_id VARCHAR(32) NOT NULL,
  sku_id VARCHAR(32) NOT NULL,
  item_name VARCHAR(255) NOT NULL,
  sku_name VARCHAR(255) NOT NULL,
  quantity INTEGER NOT NULL,
  pay_amount NUMERIC(14,2) NOT NULL,
  refund_amount NUMERIC(14,2) NOT NULL,
  kol_id BIGINT,
  product_id BIGINT,
  kol_item_id BIGINT,
  attribution_type INTEGER NOT NULL,
  attribution_time TIMESTAMPTZ(3),
  manual_reason VARCHAR(255),
  tenant_id BIGINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_order_item PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_item_id CHECK (id > 0),
  CONSTRAINT uk_crm_order_item_identity UNIQUE (order_id, sub_order_id),
  CONSTRAINT ck_crm_order_item_type CHECK (attribution_type BETWEEN 0 AND 4),
  CONSTRAINT ck_crm_order_item_quantity CHECK (quantity > 0)
);
COMMENT ON TABLE crm_order_item IS '微店订单明细；公司隔离，雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_order_item.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order_item.order_id IS '所属订单';
COMMENT ON COLUMN crm_order_item.sub_order_id IS '微店子订单号，缺失时生成稳定键';
COMMENT ON COLUMN crm_order_item.weidian_item_id IS '当前店铺微店商品 ID';
COMMENT ON COLUMN crm_order_item.sku_id IS '当前店铺 SKU ID；无 SKU 为空串';
COMMENT ON COLUMN crm_order_item.item_name IS '商品名称';
COMMENT ON COLUMN crm_order_item.sku_name IS '型号名称';
COMMENT ON COLUMN crm_order_item.quantity IS '商品件数';
COMMENT ON COLUMN crm_order_item.pay_amount IS '分摊优惠后明细实付，元';
COMMENT ON COLUMN crm_order_item.refund_amount IS '已完成退款合计，元';
COMMENT ON COLUMN crm_order_item.kol_id IS '归属达人；未匹配为空';
COMMENT ON COLUMN crm_order_item.product_id IS '原商品；无法识别为空';
COMMENT ON COLUMN crm_order_item.kol_item_id IS '命中的对应关系；无法识别为空';
COMMENT ON COLUMN crm_order_item.attribution_type IS '归因方式：0 未匹配、1 商品 ID、2 人工、3 非达人、4 冲突';
COMMENT ON COLUMN crm_order_item.attribution_time IS '归因时间 UTC；未归因为空';
COMMENT ON COLUMN crm_order_item.manual_reason IS '人工处理原因；非人工时为空';
COMMENT ON COLUMN crm_order_item.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_order_item.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_order_item.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_order_item.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_order_item.update_time IS '修改时间 UTC';
COMMENT ON COLUMN crm_order_item.is_deleted IS '逻辑删除';
CREATE INDEX idx_crm_order_item_kol_order ON crm_order_item (kol_id, order_id);
CREATE INDEX idx_crm_order_item_type ON crm_order_item (tenant_id, attribution_type);
CREATE TABLE crm_refund (
  id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  order_item_id BIGINT,
  weidian_refund_no VARCHAR(32) NOT NULL,
  amount NUMERIC(14,2) NOT NULL,
  raw_status VARCHAR(32) NOT NULL,
  refund_time TIMESTAMPTZ(3) NOT NULL,
  finish_time TIMESTAMPTZ(3),
  tenant_id BIGINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_refund PRIMARY KEY (id),
  CONSTRAINT ck_crm_refund_id CHECK (id > 0),
  CONSTRAINT uk_crm_refund_identity UNIQUE (weidian_refund_no)
);
COMMENT ON TABLE crm_refund IS '微店退款；公司隔离，雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_refund.id IS '正数雪花主键';
COMMENT ON COLUMN crm_refund.order_id IS '所属订单';
COMMENT ON COLUMN crm_refund.order_item_id IS '能唯一对应到明细时填写，否则为空';
COMMENT ON COLUMN crm_refund.weidian_refund_no IS '微店退款号';
COMMENT ON COLUMN crm_refund.amount IS '申请退款金额，分转换为元';
COMMENT ON COLUMN crm_refund.raw_status IS '微店退款状态；10 为退款完成';
COMMENT ON COLUMN crm_refund.refund_time IS '发起退款时间 UTC';
COMMENT ON COLUMN crm_refund.finish_time IS '退款完成时间 UTC；未完成为空';
COMMENT ON COLUMN crm_refund.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_refund.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_refund.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_refund.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_refund.update_time IS '修改时间 UTC';
COMMENT ON COLUMN crm_refund.is_deleted IS '逻辑删除';
CREATE INDEX idx_crm_refund_order ON crm_refund (order_id);
CREATE INDEX idx_crm_refund_item ON crm_refund (order_item_id);
CREATE TABLE crm_order_sync_state (
  id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  order_cursor TIMESTAMPTZ(3),
  refund_cursor TIMESTAMPTZ(3),
  last_success_at TIMESTAMPTZ(3),
  last_error VARCHAR(500),
  tenant_id BIGINT NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_order_sync_state PRIMARY KEY (id),
  CONSTRAINT ck_crm_order_sync_state_id CHECK (id > 0),
  CONSTRAINT uk_crm_order_sync_state_identity UNIQUE (shop_id)
);
COMMENT ON TABLE crm_order_sync_state IS '店铺订单与退款同步状态；公司隔离，雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_order_sync_state.id IS '正数雪花主键';
COMMENT ON COLUMN crm_order_sync_state.shop_id IS '所属店铺；每店一行';
COMMENT ON COLUMN crm_order_sync_state.order_cursor IS '订单成功同步到的更新时间；首次为空';
COMMENT ON COLUMN crm_order_sync_state.refund_cursor IS '退款成功同步到的更新时间；首次为空';
COMMENT ON COLUMN crm_order_sync_state.last_success_at IS '最近整批成功时间 UTC';
COMMENT ON COLUMN crm_order_sync_state.last_error IS '最近错误，安全英文，最多 500 字；成功时为空';
COMMENT ON COLUMN crm_order_sync_state.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_order_sync_state.create_by IS '创建人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_state.create_time IS '创建时间 UTC';
COMMENT ON COLUMN crm_order_sync_state.update_by IS '修改人；系统任务为空';
COMMENT ON COLUMN crm_order_sync_state.update_time IS '修改时间 UTC';
COMMENT ON COLUMN crm_order_sync_state.is_deleted IS '逻辑删除';

-- Read support for the history facade used by the snapshot correction job.
CREATE INDEX idx_crm_kol_bd_history_tenant_created ON crm_kol_bd_history (tenant_id, create_time, id) WHERE is_deleted = FALSE;
COMMENT ON INDEX idx_crm_kol_bd_history_tenant_created IS '订单商务快照修正：按公司、历史创建时间和 ID 增量读取';
