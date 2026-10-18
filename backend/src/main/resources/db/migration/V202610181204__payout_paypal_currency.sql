-- Task 25a / R18: PayPal accounts require an ISO 4217 currency; USDT leaves it null.

ALTER TABLE crm_payout_account ADD COLUMN currency CHAR(3);

UPDATE crm_payout_account
   SET currency = 'USD'
 WHERE account_type = 'PAYPAL' AND currency IS NULL;

ALTER TABLE crm_payout_account
  DROP CONSTRAINT ck_crm_payout_account_paypal,
  DROP CONSTRAINT ck_crm_payout_account_usdt;

ALTER TABLE crm_payout_account
  ADD CONSTRAINT ck_crm_payout_account_currency
    CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  ADD CONSTRAINT ck_crm_payout_account_paypal
    CHECK (
      account_type <> 'PAYPAL'
      OR (holder_name IS NOT NULL AND usdt_network IS NULL AND qr_file_id IS NULL AND currency IS NOT NULL)
    ),
  ADD CONSTRAINT ck_crm_payout_account_usdt
    CHECK (
      account_type <> 'USDT'
      OR (usdt_network IS NOT NULL AND holder_name IS NULL AND currency IS NULL)
    );

COMMENT ON COLUMN crm_payout_account.currency IS
  'PayPal 收款币种（ISO 4217 三位大写）；仅 PAYPAL 有值，USDT 为空';
