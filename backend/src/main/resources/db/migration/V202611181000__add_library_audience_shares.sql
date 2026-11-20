ALTER TABLE crm_library_kol
  ADD COLUMN audience_age_share NUMERIC(6,4),
  ADD COLUMN audience_gender_share NUMERIC(6,4),
  ADD CONSTRAINT ck_library_audience_age_share CHECK (audience_age_share BETWEEN 0 AND 100),
  ADD CONSTRAINT ck_library_audience_gender_share CHECK (audience_gender_share BETWEEN 0 AND 100);

COMMENT ON COLUMN crm_library_kol.audience_age_share IS '所选受众年龄段的占比（0 至 100），空表示未填写';
COMMENT ON COLUMN crm_library_kol.audience_gender_share IS '所选受众性别的占比（0 至 100），空表示未填写';
