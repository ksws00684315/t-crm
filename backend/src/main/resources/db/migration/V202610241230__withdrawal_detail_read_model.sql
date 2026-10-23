CREATE TABLE crm_withdrawal_detail (
 id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 kol_id BIGINT NOT NULL,
 detail_json JSONB NOT NULL CONSTRAINT ck_crm_withdrawal_detail_document CHECK (jsonb_typeof(detail_json) = 'object' AND NOT (detail_json ? 'account')),
 account_cipher TEXT NOT NULL,
 create_by BIGINT,
 create_time TIMESTAMPTZ(3) NOT NULL,
 CONSTRAINT ck_crm_withdrawal_detail_id CHECK (id > 0), CONSTRAINT pk_crm_withdrawal_detail PRIMARY KEY (id)
);
COMMENT ON TABLE crm_withdrawal_detail IS 'Read-only withdrawal detail projection; no wallet state. Historical snapshots are immutable; lifecycle projection may be replaced only by its future authoritative writer. Append-only capture omits update/delete metadata.';
COMMENT ON COLUMN crm_withdrawal_detail.id IS 'Authoritative withdrawal application ID';
COMMENT ON COLUMN crm_withdrawal_detail.tenant_id IS 'Owning company';
COMMENT ON COLUMN crm_withdrawal_detail.kol_id IS 'Creator ID for current BD access scope';
COMMENT ON COLUMN crm_withdrawal_detail.detail_json IS 'Typed WithdrawalDetailDTO document excluding account; stage remarks/history only from explicit authoritative events; unknown values remain null';
COMMENT ON COLUMN crm_withdrawal_detail.account_cipher IS 'Encrypted application-time recipient Account JSON using SecretCipher';
COMMENT ON COLUMN crm_withdrawal_detail.create_by IS 'Snapshot capture actor; null for system';
COMMENT ON COLUMN crm_withdrawal_detail.create_time IS 'Capture time UTC';
CREATE INDEX idx_crm_withdrawal_detail_tenant_kol ON crm_withdrawal_detail (tenant_id, kol_id, id);

CREATE TABLE crm_withdrawal_order_snapshot (
 id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 withdrawal_id BIGINT NOT NULL,
 order_no VARCHAR(128) NOT NULL,
 products_json JSONB NOT NULL CONSTRAINT ck_crm_withdrawal_order_snapshot_products CHECK (jsonb_typeof(products_json) = 'array'),
 occupied NUMERIC(14,2) NOT NULL CONSTRAINT ck_crm_withdrawal_order_snapshot_occupied CHECK (occupied > 0),
 remaining_snapshot NUMERIC(14,2) CONSTRAINT ck_crm_withdrawal_order_snapshot_remaining CHECK (remaining_snapshot >= 0),
 create_by BIGINT,
 create_time TIMESTAMPTZ(3) NOT NULL,
 CONSTRAINT ck_crm_withdrawal_order_snapshot_id CHECK (id > 0), CONSTRAINT pk_crm_withdrawal_order_snapshot PRIMARY KEY (id),
 CONSTRAINT uk_crm_withdrawal_order_snapshot_order UNIQUE (tenant_id, withdrawal_id, order_no)
);
COMMENT ON TABLE crm_withdrawal_order_snapshot IS 'Immutable application-time order allocations. Append-only snapshots omit update/delete metadata; no current-wallet reconstruction.';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.id IS 'Snowflake snapshot ID; stable pagination order';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.tenant_id IS 'Owning company';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.withdrawal_id IS 'Authoritative withdrawal application ID';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.order_no IS 'Order number, one row per distinct order per application';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.products_json IS 'Application-time product list with string id, image, product name and SKU';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.occupied IS 'Total CNY occupied by this application on this order';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.remaining_snapshot IS 'Order CNY available immediately after this application allocation; NULL means historical order snapshot unavailable; never sum product snapshots';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.create_by IS 'Snapshot capture actor; null for system';
COMMENT ON COLUMN crm_withdrawal_order_snapshot.create_time IS 'Capture time UTC';
CREATE INDEX idx_crm_withdrawal_order_snapshot_page ON crm_withdrawal_order_snapshot (tenant_id, withdrawal_id, id);
