-- Task 40 (row 39, 08.02): refund type and normalized refund status on crm_refund.
-- Real Weidian values stay in raw_status; the mapping lives in RefundDictionary (design §8.1a).

ALTER TABLE crm_refund ADD COLUMN refund_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE crm_refund ADD COLUMN refund_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE crm_refund ADD CONSTRAINT ck_crm_refund_type
  CHECK (refund_type IN ('REFUND_ONLY', 'RETURN_REFUND', 'UNKNOWN'));
ALTER TABLE crm_refund ADD CONSTRAINT ck_crm_refund_status
  CHECK (refund_status IN ('PROCESSING', 'SUCCESS', 'CLOSED', 'UNKNOWN'));

-- Backfill from the one mapping the design already fixes: raw status 10 is a completed refund.
UPDATE crm_refund SET refund_status = 'SUCCESS' WHERE raw_status = '10';

CREATE INDEX idx_crm_refund_tenant_status ON crm_refund (tenant_id, refund_status);

COMMENT ON COLUMN crm_refund.refund_type IS '退款类型：REFUND_ONLY 仅退款 / RETURN_REFUND 退货退款 / UNKNOWN（真实取值待 M0 核实）';
COMMENT ON COLUMN crm_refund.refund_status IS '标准化退款状态：PROCESSING 退款中 / SUCCESS 退款成功 / CLOSED 已关闭 / UNKNOWN；由 raw_status 映射';
