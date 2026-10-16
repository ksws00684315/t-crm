-- Task 15a: KOL payout accounts (PayPal / USDT) and business files (QR images; later also
-- withdrawal vouchers and prepay attachments). Append-only file storage, encrypted accounts.

-- 1. Business files: stored in the database like crm_task_file; only PNG/JPEG arrive here today
-- (validated by magic bytes), PDF joins with the voucher types later. biz_id is backfilled when
-- the business row that owns the file is saved; until then it stays null.
CREATE TABLE crm_file (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  biz_type VARCHAR(32) NOT NULL,
  biz_id BIGINT,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  content BYTEA NOT NULL,
  create_by BIGINT NOT NULL,
  create_by_type VARCHAR(8) NOT NULL,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_file PRIMARY KEY (id),
  CONSTRAINT ck_crm_file_id CHECK (id > 0),
  CONSTRAINT ck_crm_file_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_file_biz_type CHECK (biz_type IN ('PAYOUT_QR', 'WITHDRAWAL_VOUCHER', 'PREPAY_ATTACHMENT', 'PREPAY_VOUCHER')),
  CONSTRAINT ck_crm_file_biz_id CHECK (biz_id IS NULL OR biz_id > 0),
  CONSTRAINT ck_crm_file_size CHECK (size_bytes > 0 AND size_bytes <= 2097152),
  CONSTRAINT ck_crm_file_actor_type CHECK (create_by_type IN ('ADMIN', 'KOL'))
);
-- Files of one business record (QR of an account, voucher of a withdrawal).
CREATE INDEX idx_crm_file_biz ON crm_file (biz_type, biz_id);
COMMENT ON TABLE crm_file IS '业务文件（收款二维码、提现打款凭证、预付附件与凭证）；存数据库，只追加，只经鉴权接口以附件下载；biz_id 在业务记录保存时回填';
COMMENT ON COLUMN crm_file.id IS '正数雪花主键';
COMMENT ON COLUMN crm_file.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_file.biz_type IS '业务类型：PAYOUT_QR 收款二维码、WITHDRAWAL_VOUCHER 提现打款凭证、PREPAY_ATTACHMENT 预付附件、PREPAY_VOUCHER 预付凭证';
COMMENT ON COLUMN crm_file.biz_id IS '所属业务记录 id（PAYOUT_QR 为 crm_payout_account.id）；账户创建前先上传时为空，保存账户时回填';
COMMENT ON COLUMN crm_file.file_name IS '上传时的原始文件名；仅供下载时展示';
COMMENT ON COLUMN crm_file.content_type IS '按文件头识别的实际类型：image/png 或 image/jpeg';
COMMENT ON COLUMN crm_file.size_bytes IS '文件字节数；上传上限 2MB';
COMMENT ON COLUMN crm_file.content IS '文件内容';
COMMENT ON COLUMN crm_file.create_by IS '上传人 ID：KOL 上传为 crm_kol.id，管理员上传为 sys_user.id（配合 create_by_type 区分）';
COMMENT ON COLUMN crm_file.create_by_type IS '上传人类型：ADMIN 管理员、KOL 达人';
COMMENT ON COLUMN crm_file.create_time IS '上传时间（UTC）';

-- 2. Payout accounts: entered by the KOL only, reviewed by the owning BD or a super admin.
-- The account (PayPal id or wallet address) is encrypted; every read path shows the masked value
-- except the KOL's own list and the reviewer's detail view.
CREATE TABLE crm_payout_account (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  account_type VARCHAR(8) NOT NULL,
  account_cipher VARCHAR(512) NOT NULL,
  account_masked VARCHAR(160) NOT NULL,
  holder_name VARCHAR(64),
  usdt_network VARCHAR(8),
  qr_file_id BIGINT,
  status VARCHAR(16) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  reject_reason VARCHAR(255),
  reviewed_by BIGINT,
  reviewed_time TIMESTAMPTZ(3),
  last_submitted_at TIMESTAMPTZ(3) NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_payout_account PRIMARY KEY (id),
  CONSTRAINT ck_crm_payout_account_id CHECK (id > 0),
  CONSTRAINT ck_crm_payout_account_tenant CHECK (tenant_id > 0),
  CONSTRAINT ck_crm_payout_account_type CHECK (account_type IN ('PAYPAL', 'USDT')),
  CONSTRAINT ck_crm_payout_account_network CHECK (usdt_network IN ('TRC20', 'ERC20', 'BEP20', 'SOL', 'TON')),
  CONSTRAINT ck_crm_payout_account_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED')),
  CONSTRAINT ck_crm_payout_account_cipher CHECK (account_cipher LIKE 'v1:%'),
  CONSTRAINT ck_crm_payout_account_paypal CHECK (account_type <> 'PAYPAL' OR (holder_name IS NOT NULL AND usdt_network IS NULL AND qr_file_id IS NULL)),
  CONSTRAINT ck_crm_payout_account_usdt CHECK (account_type <> 'USDT' OR (usdt_network IS NOT NULL AND holder_name IS NULL)),
  CONSTRAINT ck_crm_payout_account_default CHECK (status <> 'DISABLED' OR is_default = FALSE),
  CONSTRAINT ck_crm_payout_account_reject CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL)),
  CONSTRAINT ck_crm_payout_account_review CHECK (
    status IN ('PENDING', 'DISABLED') OR (reviewed_by IS NOT NULL AND reviewed_time IS NOT NULL)
  )
);
-- At most one default account per KOL.
CREATE UNIQUE INDEX uk_crm_payout_account_default ON crm_payout_account (kol_id) WHERE is_default;
-- KOL's own list and per-status lookups.
CREATE INDEX idx_crm_payout_account_kol_status ON crm_payout_account (kol_id, status);
-- Review queue and admin list in submission order.
CREATE INDEX idx_crm_payout_account_queue ON crm_payout_account (tenant_id, status, last_submitted_at);
COMMENT ON TABLE crm_payout_account IS '达人收款账户（PayPal / USDT）；由达人本人填写，所属商务或超管审核；通过后锁定，停用不可恢复；不删除，故无逻辑删除列';
COMMENT ON COLUMN crm_payout_account.id IS '正数雪花主键';
COMMENT ON COLUMN crm_payout_account.tenant_id IS '所属公司 sys_tenant.id';
COMMENT ON COLUMN crm_payout_account.kol_id IS '达人 crm_kol.id';
COMMENT ON COLUMN crm_payout_account.account_type IS '账户类型：PAYPAL、USDT';
COMMENT ON COLUMN crm_payout_account.account_cipher IS 'PayPal 账号或钱包地址的 SecretCipher 密文（v1: 前缀）；明文只在达人本人列表与审核详情接口解密返回，不入日志';
COMMENT ON COLUMN crm_payout_account.account_masked IS '脱敏显示值（邮箱前 2 位 + *** + @域名；其他前 4 位 + … + 后 4 位，8 位以下只保留首尾各 1 位）';
COMMENT ON COLUMN crm_payout_account.holder_name IS 'PayPal 收款人姓名；仅 PAYPAL 有值';
COMMENT ON COLUMN crm_payout_account.usdt_network IS 'USDT 网络：TRC20、ERC20、BEP20、SOL、TON；仅 USDT 有值';
COMMENT ON COLUMN crm_payout_account.qr_file_id IS '收款二维码 crm_file.id（USDT 可选，PNG/JPEG，≤2MB）';
COMMENT ON COLUMN crm_payout_account.status IS '状态：PENDING 待审核、APPROVED 已通过（锁定不可修改）、REJECTED 已驳回（修改后重新提交）、DISABLED 已停用（不可恢复）';
COMMENT ON COLUMN crm_payout_account.is_default IS '是否默认账户；只能在 APPROVED 账户中设置，每位达人最多一个，停用时自动取消';
COMMENT ON COLUMN crm_payout_account.reject_reason IS '驳回原因；仅 REJECTED 有值，重新提交或停用时清空';
COMMENT ON COLUMN crm_payout_account.reviewed_by IS '审核人 sys_user.id；PENDING 为空，DISABLED 保留最后一次审核人';
COMMENT ON COLUMN crm_payout_account.reviewed_time IS '审核时间（UTC）；PENDING 为空，DISABLED 保留最后一次审核时间';
COMMENT ON COLUMN crm_payout_account.last_submitted_at IS '最近一次提交（新建或修改后重新提交）时间（UTC）；管理端列表默认按此倒序';
COMMENT ON COLUMN crm_payout_account.create_by IS '创建人：恒为达人 crm_kol.id（账户由达人本人填写）';
COMMENT ON COLUMN crm_payout_account.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_payout_account.update_by IS '修改人 ID：达人修改时为 crm_kol.id，审核、停用时为管理员 sys_user.id';
COMMENT ON COLUMN crm_payout_account.update_time IS '修改时间（UTC）';
