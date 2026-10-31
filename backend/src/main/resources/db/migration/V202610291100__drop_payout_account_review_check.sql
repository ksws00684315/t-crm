-- Task 82 (2026-10-29, owner decision): payout accounts are no longer reviewed. A new account is
-- APPROVED at once, and the KOL may edit any account that is not disabled. The review columns stay
-- as history; only the check that required a reviewer on APPROVED rows is dropped.

ALTER TABLE crm_payout_account DROP CONSTRAINT ck_crm_payout_account_review;

COMMENT ON TABLE crm_payout_account IS '达人收款账户（PayPal / USDT）；由达人本人填写，新建与修改即时生效，不再审核（2026-10-29 起）；停用不可恢复；不删除，故无逻辑删除列';
COMMENT ON COLUMN crm_payout_account.status IS '状态：APPROVED 生效（2026-10-29 起新建即为生效，可修改）、DISABLED 已停用（不可恢复）；PENDING、REJECTED 为历史取值，2026-10-29 起不再产生';
COMMENT ON COLUMN crm_payout_account.reject_reason IS '历史字段，2026-10-29 起不再审核';
COMMENT ON COLUMN crm_payout_account.reviewed_by IS '历史字段，2026-10-29 起不再审核';
COMMENT ON COLUMN crm_payout_account.reviewed_time IS '历史字段，2026-10-29 起不再审核';
