-- Task 29 / ADR-025: revoke and remove 32-char codes; switch CHECK to 8-char (no 0/O/1/I/L).

UPDATE crm_kol_register_code
SET status = 0,
    update_time = CLOCK_TIMESTAMP()
WHERE status = 1
  AND char_length(code) <> 8;

-- Historical 32-char rows cannot satisfy the new CHECK; they are unused after revoke.
DELETE FROM crm_kol_register_code WHERE char_length(code) <> 8;

ALTER TABLE crm_kol_register_code
  DROP CONSTRAINT ck_crm_kol_register_code_code;

ALTER TABLE crm_kol_register_code
  ADD CONSTRAINT ck_crm_kol_register_code_code
    CHECK (code ~ '^[2-9A-HJ-NP-Z]{8}$');

COMMENT ON COLUMN crm_kol_register_code.code IS
  '8 位商务邀请码（大写字母与数字，去掉 0/O/1/I/L），全表唯一，输入不区分大小写；出现在达人端 /register?code= 链接中';
