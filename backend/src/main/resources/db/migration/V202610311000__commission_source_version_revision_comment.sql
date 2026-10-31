-- Task 83: source versions now carry the revision number, so a returning fingerprint stays unique. Comments only.

COMMENT ON COLUMN crm_commission_item_state.source_version IS '当前来源版本：{来源快照指纹 MD5}#{修订序号}（任务 83）；任务 83 之前的行为不带序号的指纹；上线回填行为空，表示结算引擎尚未登记过版本';
COMMENT ON COLUMN crm_commission_item_revision.source_version IS '本修订的来源版本：{来源快照指纹 MD5}#{修订序号}（任务 83），指纹回到历史值时序号仍递增；同一商品行同一版本至多一条；任务 83 之前的修订为不带序号的指纹';
