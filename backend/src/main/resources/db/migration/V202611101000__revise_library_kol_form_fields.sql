-- Creator library form revision of 2026-11-10 (task 146 §2.1.1, requirement 1.2.5 ④, 1.2.6 ①):
-- a follow-up remark separate from the profile remark, content categories no longer required, and
-- the cooperation stage retired. followers and profile_url stay nullable in the table because older
-- rows may lack them; the API requires them from now on. No existing data is changed.

ALTER TABLE crm_library_kol ADD COLUMN follow_up_remark VARCHAR(1000);
COMMENT ON COLUMN crm_library_kol.follow_up_remark IS
  '跟进备注（建联及寄样），最多 1000 字符；空表示无；与档案备注 remark 是两个字段（2026-11-10 新增）；不写入操作日志';

-- Categories become optional: an empty array is allowed, the column stays NOT NULL ([] when none).
ALTER TABLE crm_library_kol DROP CONSTRAINT ck_crm_library_kol_categories;
ALTER TABLE crm_library_kol ADD CONSTRAINT ck_crm_library_kol_categories
  CHECK (jsonb_typeof(categories) = 'array');
COMMENT ON COLUMN crm_library_kol.categories IS
  '内容类目 JSON 数组，可为空数组 []（2026-11-10 起非必填）：BEAUTY_PERSONAL_CARE、HEALTH_SUPPLEMENTS、HOME_DAILY、ELECTRONICS_3C、FASHION_ACCESSORIES、MATERNAL_BABY、FOOD_BEVERAGE、PET、SPORTS_OUTDOOR、OTHER';

COMMENT ON COLUMN crm_library_kol.cooperation_stage IS
  '合作阶段（2026-11-10 起停用，应用不再读写，只保留历史数据）：FIRST_COOPERATION 初次合作、REPEAT_INVESTMENT 复投中、LONG_TERM_PARTNERSHIP 长期深度合作、EXCLUSIVE 独家；空表示未填写';
COMMENT ON COLUMN crm_library_kol.followers IS
  '粉丝数，非负整数；2026-11-10 起接口必填，库中保持可空（老数据可能为空）';
COMMENT ON COLUMN crm_library_kol.profile_url IS
  '主页链接，http/https，最多 512 字符；2026-11-10 起接口必填，库中保持可空（老数据可能为空）';
COMMENT ON COLUMN crm_library_kol.avg_views IS '近期平均播放量，非负整数；空表示未填写';
COMMENT ON COLUMN crm_library_kol.contact IS '联系方式，最多 128 字符；空表示未填写；不写入操作日志';
COMMENT ON COLUMN crm_library_kol.tracking_no IS '寄样单号，最多 64 字符；空表示未填写';
