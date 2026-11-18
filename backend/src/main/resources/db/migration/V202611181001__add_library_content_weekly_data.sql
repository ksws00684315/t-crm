ALTER TABLE crm_library_kol_content
  ADD COLUMN publish_title VARCHAR(200),
  ADD COLUMN weekly_data JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD CONSTRAINT ck_library_content_weekly_data_array CHECK (jsonb_typeof(weekly_data) = 'array');

COMMENT ON COLUMN crm_library_kol_content.publish_title IS '视频标题，历史内容可为空';
COMMENT ON COLUMN crm_library_kol_content.weekly_data IS '已提交的各自然周数据，周一为起始日，按内容记录追加，空数组表示未录入';
