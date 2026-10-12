-- Task 06d: the language the import was submitted in, used to render its error file (design §2.4).
-- Existing tasks were all submitted with Chinese templates, so they become zh-CN.
ALTER TABLE crm_task ADD COLUMN locale VARCHAR(8) NOT NULL DEFAULT 'zh-CN';
ALTER TABLE crm_task ALTER COLUMN locale DROP DEFAULT;
ALTER TABLE crm_task ADD CONSTRAINT ck_crm_task_locale CHECK (locale IN ('zh-CN', 'en-US'));
COMMENT ON COLUMN crm_task.locale IS '提交时的请求语言：zh-CN 或 en-US；错误文件按此语言生成，提交后不变';
