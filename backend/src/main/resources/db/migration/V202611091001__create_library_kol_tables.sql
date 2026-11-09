-- TikTok creator library of the KOL_LIBRARY edition (ADR-038 §4, task 143 §3.1). New tables with
-- their own tenant_id; crm_kol and the kol module are deliberately not reused (ADR-038 §4).
-- Calculated fields (estimated commission, total cost, ROI, totals) are never stored.

CREATE TABLE crm_library_kol (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  platform VARCHAR(16) NOT NULL,
  kol_username VARCHAR(64) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  followers BIGINT,
  country_code VARCHAR(8) NOT NULL,
  profile_url VARCHAR(512),
  categories JSONB NOT NULL,
  avg_views BIGINT,
  engagement_rate NUMERIC(6,2),
  audience_country VARCHAR(64),
  audience_age_range VARCHAR(8),
  audience_gender VARCHAR(16),
  contact VARCHAR(128),
  content_quality VARCHAR(8),
  sales_potential VARCHAR(8),
  cooperation_attitude VARCHAR(8),
  grade VARCHAR(4),
  cooperation_stage VARCHAR(24),
  tags JSONB NOT NULL,
  remark VARCHAR(1000),
  outreach_status VARCHAR(16) NOT NULL,
  follow_up_owner VARCHAR(64) NOT NULL,
  last_contact_date DATE,
  sample_status VARCHAR(16) NOT NULL,
  tracking_no VARCHAR(64),
  sample_date DATE,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_library_kol PRIMARY KEY (id),
  CONSTRAINT ck_crm_library_kol_id CHECK (id > 0),
  CONSTRAINT ck_crm_library_kol_platform
    CHECK (platform IN ('TIKTOK', 'INSTAGRAM', 'YOUTUBE', 'OTHER')),
  CONSTRAINT ck_crm_library_kol_username
    CHECK (kol_username ~ '^[a-z0-9._]{1,64}$'),
  CONSTRAINT ck_crm_library_kol_categories
    CHECK (jsonb_typeof(categories) = 'array' AND jsonb_array_length(categories) >= 1),
  CONSTRAINT ck_crm_library_kol_tags CHECK (jsonb_typeof(tags) = 'array'),
  CONSTRAINT ck_crm_library_kol_followers CHECK (followers >= 0),
  CONSTRAINT ck_crm_library_kol_avg_views CHECK (avg_views >= 0),
  CONSTRAINT ck_crm_library_kol_engagement CHECK (engagement_rate >= 0 AND engagement_rate <= 100),
  CONSTRAINT ck_crm_library_kol_age_range
    CHECK (audience_age_range IN ('AGE_18_24', 'AGE_25_34', 'AGE_35_44', 'AGE_45_54', 'AGE_55_PLUS')),
  CONSTRAINT ck_crm_library_kol_gender
    CHECK (audience_gender IN ('MOSTLY_FEMALE', 'MOSTLY_MALE', 'BALANCED')),
  CONSTRAINT ck_crm_library_kol_level
    CHECK (content_quality IS NULL OR content_quality IN ('HIGH', 'MEDIUM', 'LOW')),
  CONSTRAINT ck_crm_library_kol_sales_potential
    CHECK (sales_potential IS NULL OR sales_potential IN ('HIGH', 'MEDIUM', 'LOW')),
  CONSTRAINT ck_crm_library_kol_attitude
    CHECK (cooperation_attitude IS NULL OR cooperation_attitude IN ('HIGH', 'MEDIUM', 'LOW')),
  CONSTRAINT ck_crm_library_kol_grade
    CHECK (grade IS NULL OR grade IN ('S', 'A', 'B', 'C')),
  CONSTRAINT ck_crm_library_kol_stage
    CHECK (cooperation_stage IS NULL
           OR cooperation_stage IN ('FIRST_COOPERATION', 'REPEAT_INVESTMENT',
                                    'LONG_TERM_PARTNERSHIP', 'EXCLUSIVE')),
  CONSTRAINT ck_crm_library_kol_outreach
    CHECK (outreach_status IN ('NOT_CONTACTED', 'EMAIL_SENT', 'REPLIED', 'WHATSAPP_ADDED',
                               'REJECTED', 'COOPERATING')),
  CONSTRAINT ck_crm_library_kol_sample
    CHECK (sample_status IN ('NOT_SENT', 'SHIPPED', 'RECEIVED', 'NOT_NEEDED'))
);
-- Same creator cannot be entered twice in one company (requirement 1.2.6 ②), soft-deleted rows
-- excluded so a deleted creator can be entered again.
CREATE UNIQUE INDEX uk_crm_library_kol_username
  ON crm_library_kol (tenant_id, platform, kol_username) WHERE is_deleted = FALSE;
-- List order: newest change first.
CREATE INDEX idx_crm_library_kol_update ON crm_library_kol (tenant_id, update_time DESC, id DESC)
  WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_library_kol IS
  '达人库达人档案（TikTok 达人库一期，ADR-038 §4）；与 A 公司 crm_kol 完全独立；雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_library_kol.id IS '正数雪花主键';
COMMENT ON COLUMN crm_library_kol.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_library_kol.platform IS
  '所属平台：TIKTOK 抖音国际版、INSTAGRAM、YOUTUBE、OTHER 其他';
COMMENT ON COLUMN crm_library_kol.kol_username IS
  '达人ID（用户名）：保存时去首尾空格、去开头 @、转小写；公司内「平台 + 达人ID」唯一';
COMMENT ON COLUMN crm_library_kol.display_name IS '达人名称（昵称），可含空格与表情；不参与判重；1～100 字符';
COMMENT ON COLUMN crm_library_kol.followers IS '粉丝数，非负整数；空表示未填写';
COMMENT ON COLUMN crm_library_kol.country_code IS
  '国家站点：US、GB、MX、DE、FR、IT、ES、JP、ID、TH、VN、MY、PH、SG、OTHER（其他）';
COMMENT ON COLUMN crm_library_kol.profile_url IS '主页链接，http/https，最多 512 字符；空表示未填写';
COMMENT ON COLUMN crm_library_kol.categories IS
  '内容类目 JSON 数组，至少一项：BEAUTY_PERSONAL_CARE、HEALTH_SUPPLEMENTS、HOME_DAILY、ELECTRONICS_3C、FASHION_ACCESSORIES、MATERNAL_BABY、FOOD_BEVERAGE、PET、SPORTS_OUTDOOR、OTHER';
COMMENT ON COLUMN crm_library_kol.avg_views IS '平均播放量，非负整数；空表示未填写';
COMMENT ON COLUMN crm_library_kol.engagement_rate IS '互动率百分比 0～100，两位小数；空表示未填写';
COMMENT ON COLUMN crm_library_kol.audience_country IS '受众国家/地区，自由文本，最多 64 字符';
COMMENT ON COLUMN crm_library_kol.audience_age_range IS
  '受众年龄段：AGE_18_24 18~24、AGE_25_34 25~34、AGE_35_44 35~44、AGE_45_54 45~54、AGE_55_PLUS 55+；空表示未填写';
COMMENT ON COLUMN crm_library_kol.audience_gender IS
  '受众性别：MOSTLY_FEMALE 女性为主、MOSTLY_MALE 男性为主、BALANCED 均衡；空表示未填写';
COMMENT ON COLUMN crm_library_kol.contact IS '联系方式 WhatsApp/TEL，最多 128 字符；空表示未填写；不写入操作日志';
COMMENT ON COLUMN crm_library_kol.content_quality IS '内容质量：HIGH 高、MEDIUM 中、LOW 低；空表示未评定';
COMMENT ON COLUMN crm_library_kol.sales_potential IS '带货潜力：HIGH 高、MEDIUM 中、LOW 低；空表示未评定';
COMMENT ON COLUMN crm_library_kol.cooperation_attitude IS '配合度：HIGH 高、MEDIUM 中、LOW 低；空表示未评定';
COMMENT ON COLUMN crm_library_kol.grade IS '达人分级：S、A、B、C；一期人工评定，空表示未评级';
COMMENT ON COLUMN crm_library_kol.cooperation_stage IS
  '合作阶段：FIRST_COOPERATION 初次合作、REPEAT_INVESTMENT 复投中、LONG_TERM_PARTNERSHIP 长期深度合作、EXCLUSIVE 独家；空表示未填写';
COMMENT ON COLUMN crm_library_kol.tags IS '自由标签 JSON 数组（如 高转化、坑位费贵），最多 10 个，每个最多 20 字符；无标签时为 []';
COMMENT ON COLUMN crm_library_kol.remark IS '备注，最多 1000 字符；空表示无备注';
COMMENT ON COLUMN crm_library_kol.outreach_status IS
  '建联状态：NOT_CONTACTED 未建联（默认）、EMAIL_SENT 已发邮件、REPLIED 已回复、WHATSAPP_ADDED 已加 WhatsApp、REJECTED 拒绝、COOPERATING 合作中';
COMMENT ON COLUMN crm_library_kol.follow_up_owner IS '跟进负责人（商务手动填写的文本，不关联系统账号），最多 64 字符';
COMMENT ON COLUMN crm_library_kol.last_contact_date IS '最近沟通时间（日期，不涉及时区换算）；空表示未填写';
COMMENT ON COLUMN crm_library_kol.sample_status IS
  '寄样状态：NOT_SENT 未寄样（默认）、SHIPPED 已寄出、RECEIVED 已签收、NOT_NEEDED 无需寄样';
COMMENT ON COLUMN crm_library_kol.tracking_no IS '快递单号，最多 64 字符；空表示未填写';
COMMENT ON COLUMN crm_library_kol.sample_date IS '寄样日期（日期）；空表示未填写';
COMMENT ON COLUMN crm_library_kol.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_library_kol.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_library_kol.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_library_kol.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_library_kol.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_library_kol_content (
  id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  kol_id BIGINT NOT NULL,
  publish_url VARCHAR(512) NOT NULL,
  publish_date DATE NOT NULL,
  content_form VARCHAR(16) NOT NULL,
  clicks BIGINT,
  orders BIGINT,
  gmv NUMERIC(14,2),
  sample_type VARCHAR(16),
  sample_cost NUMERIC(14,2),
  slot_fee NUMERIC(14,2),
  commission_rate NUMERIC(6,2),
  next_action VARCHAR(1000),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_library_kol_content PRIMARY KEY (id),
  CONSTRAINT ck_crm_library_kol_content_id CHECK (id > 0),
  CONSTRAINT ck_crm_library_kol_content_form
    CHECK (content_form IN ('SHORT_VIDEO', 'LIVE', 'POST')),
  CONSTRAINT ck_crm_library_kol_content_sample
    CHECK (sample_type IS NULL OR sample_type IN ('GIFT', 'PHOTO_SHOOT')),
  CONSTRAINT ck_crm_library_kol_content_clicks CHECK (clicks >= 0),
  CONSTRAINT ck_crm_library_kol_content_orders CHECK (orders >= 0),
  CONSTRAINT ck_crm_library_kol_content_gmv CHECK (gmv >= 0),
  CONSTRAINT ck_crm_library_kol_content_sample_cost CHECK (sample_cost >= 0),
  CONSTRAINT ck_crm_library_kol_content_slot_fee CHECK (slot_fee >= 0),
  CONSTRAINT ck_crm_library_kol_content_rate
    CHECK (commission_rate >= 0 AND commission_rate <= 100)
);
-- Content records of one creator, newest publication first.
CREATE INDEX idx_crm_library_kol_content_kol
  ON crm_library_kol_content (tenant_id, kol_id, publish_date DESC, id DESC)
  WHERE is_deleted = FALSE;
COMMENT ON TABLE crm_library_kol_content IS
  '达人库内容与带货产出记录（TikTok 达人库一期）；一条记录对应达人发布的一条内容；预估佣金、总投入成本、实际 ROI 由应用计算，不入库；雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_library_kol_content.id IS '正数雪花主键';
COMMENT ON COLUMN crm_library_kol_content.tenant_id IS '所属公司（sys_tenant.id）';
COMMENT ON COLUMN crm_library_kol_content.kol_id IS '达人档案 crm_library_kol.id；归属由应用校验';
COMMENT ON COLUMN crm_library_kol_content.publish_url IS '发布内容链接，http/https，最多 512 字符';
COMMENT ON COLUMN crm_library_kol_content.publish_date IS '发布日期（日期）';
COMMENT ON COLUMN crm_library_kol_content.content_form IS '内容形式：SHORT_VIDEO 短视频、LIVE 直播、POST 图文';
COMMENT ON COLUMN crm_library_kol_content.clicks IS '点击量，非负整数；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.orders IS '出单量，非负整数；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.gmv IS '带货 GMV（人民币），两位小数，非负；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.sample_type IS '样品情况：GIFT 送样、PHOTO_SHOOT 寄拍；空表示未填写';
COMMENT ON COLUMN crm_library_kol_content.sample_cost IS '样品成本（人民币），两位小数，非负；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.slot_fee IS '坑位费（人民币），两位小数，非负；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.commission_rate IS '佣金比例百分比 0～100，两位小数；空按 0 计';
COMMENT ON COLUMN crm_library_kol_content.next_action IS '下一步行动计划，最多 1000 字符；空表示未填写';
COMMENT ON COLUMN crm_library_kol_content.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_library_kol_content.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_library_kol_content.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_library_kol_content.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_library_kol_content.is_deleted IS '逻辑删除：false 有效、true 已删除';
