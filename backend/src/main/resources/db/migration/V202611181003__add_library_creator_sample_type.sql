ALTER TABLE crm_library_kol
  ADD COLUMN sample_type VARCHAR(16),
  ADD CONSTRAINT ck_library_kol_sample_type CHECK (sample_type IN ('GIFT', 'PHOTO_SHOOT'));

COMMENT ON COLUMN crm_library_kol.sample_type IS '达人样品类型：GIFT 送样、PHOTO_SHOOT 寄拍；空表示未填写';
