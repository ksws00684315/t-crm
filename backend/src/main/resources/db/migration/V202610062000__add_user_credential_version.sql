-- Credential version (security review of PR #24): bumped by every password change or reset and
-- bound to each admin token at login; a token whose version differs from the account is rejected,
-- so a reset also revokes tokens issued concurrently or whose Redis logout failed.
ALTER TABLE sys_user ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_credential_version CHECK (credential_version >= 0);
COMMENT ON COLUMN sys_user.credential_version IS '凭据版本：每次修改或重置密码加 1；登录时写入令牌会话，每次请求比对，不一致则令牌失效';
