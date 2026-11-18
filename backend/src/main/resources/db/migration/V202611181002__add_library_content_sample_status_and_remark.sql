ALTER TABLE crm_library_kol_content
  ADD COLUMN sample_status VARCHAR(16),
  ADD COLUMN remark VARCHAR(1000),
  ADD CONSTRAINT ck_library_content_sample_status
    CHECK (sample_status IN ('NOT_SENT', 'SHIPPED', 'RECEIVED', 'NOT_NEEDED'));

COMMENT ON COLUMN crm_library_kol_content.sample_status IS '内容样品状态：NOT_SENT 未寄样、SHIPPED 已寄出、RECEIVED 已签收、NOT_NEEDED 无需寄样；空表示未填写';
COMMENT ON COLUMN crm_library_kol_content.remark IS '内容记录备注，空表示未填写';
