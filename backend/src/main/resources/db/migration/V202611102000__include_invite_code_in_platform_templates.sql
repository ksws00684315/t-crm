-- 2026-11-10 / 飞书单「平台模板补达人邀请码」：系统维护的代购平台模板全部缺少 {INVITE_CODE}，
-- 表格脚本生成的平台链接因此不带达人邀请码。按交付脚本 SparkTrack-3.0.5 的 getModel() 原模板
-- （29 个平台，均含 {INVITE_CODE}）补齐现有平台；只更新地址与脚本模板完全一致的行，
-- 客户手工改过的地址保持不动，重复执行不会再命中（幂等）。
--
-- rizzitgo、mycnbox、loongbuy、eastmallbuy 四个平台在脚本原模板里没有，需客户提供邀请码参数后再补。
-- 停用的 testbuy 测试行不动。
-- 模板里的 {INVITE_CODE} 由 LinkUrls 用达人已存邀请码替换；达人没有邀请码时按既有规则回退微店直链。
-- 表格脚本侧不需要改代码：V2 从系统 /admin-api/external/product-links/templates 读取模板。

UPDATE crm_link_template
SET template_url = 'https://www.kakobuy.com/item/details?url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}&affcode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'kakobuy'
  AND template_url = 'https://www.kakobuy.com/item/details?url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.acbuy.com/product?id={PRODUCT_ID}&source=WD&u={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'acbuy'
  AND template_url = 'https://www.acbuy.com/product?id={PRODUCT_ID}&source=WD'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://oopbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'oopbuy'
  AND template_url = 'https://oopbuy.com/product/weidian/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://litbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'litbuy'
  AND template_url = 'https://litbuy.com/product/weidian/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.usfans.com/product/3/{PRODUCT_ID}?ref={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'usfans'
  AND template_url = 'https://www.usfans.com/product/3/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://cnfans.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'cnfans'
  AND template_url = 'https://cnfans.com/product/?shop_type=weidian&id={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.sugargoo.com/products?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}&memberId={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'sugargoo'
  AND template_url = 'https://www.sugargoo.com/products?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://hipobuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'hipobuy'
  AND template_url = 'https://hipobuy.com/product/weidian/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://hubbuycn.com/product/item?url=https://weidian.com/item.html?itemID={PRODUCT_ID}&invitation_code={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'hubbuycn'
  AND template_url = 'https://hubbuycn.com/product/item?url=https://weidian.com/item.html?itemID={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://gtbuy.com/product/weidian/{PRODUCT_ID}?inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'gtbuy'
  AND template_url = 'https://gtbuy.com/product/weidian/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.itaobuy.com/product-detail?url=https://weidian.com/item.html?itemID={PRODUCT_ID}&inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'itaobuy'
  AND template_url = 'https://www.itaobuy.com/product-detail?url=https://weidian.com/item.html?itemID={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.fishgoo.com/#/product?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}&memberId={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'fishgoo'
  AND template_url = 'https://www.fishgoo.com/#/product?productLink=https://weidian.com/item.html?itemID={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://orientdig.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'orientdig'
  AND template_url = 'https://orientdig.com/product/?shop_type=weidian&id={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.superbuy.com/en/page/buy/?nTag=Home-search&from=search-input&url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}&partnercode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'superbuy'
  AND template_url = 'https://www.superbuy.com/en/page/buy/?nTag=Home-search&from=search-input&url=https%3A%2F%2Fweidian.com%2Fitem.html%3FitemID%3D{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://mulebuy.com/product/?shop_type=weidian&id={PRODUCT_ID}&ref={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'mulebuy'
  AND template_url = 'https://mulebuy.com/product/?shop_type=weidian&id={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.cssbuy.com/item-micro-{PRODUCT_ID}.html?promotionCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'cssbuy'
  AND template_url = 'https://www.cssbuy.com/item-micro-{PRODUCT_ID}.html'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://hoobuy.com/product/2/{PRODUCT_ID}?inviteCode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'hoobuy'
  AND template_url = 'https://hoobuy.com/product/2/{PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.allchinabuy.com/en/page/buy/?nTag=Home-search&from=search-input&_search=url&position=&url=https://weidian.com/item.html?itemID={PRODUCT_ID}&partnercode={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'allchinabuy'
  AND template_url = 'https://www.allchinabuy.com/en/page/buy/?nTag=Home-search&from=search-input&_search=url&position=&url=https://weidian.com/item.html?itemID={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://joyagoo.com/product?platform=WEIDIAN&id={PRODUCT_ID}&ref={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'joyagoo'
  AND template_url = 'https://joyagoo.com/product?platform=WEIDIAN&id={PRODUCT_ID}'
  AND is_deleted = FALSE;

UPDATE crm_link_template
SET template_url = 'https://www.lovegobuy.com/product?shop_type=weidian&id={PRODUCT_ID}&invite_code={INVITE_CODE}',
    update_time = now()
WHERE template_code = 'lovegobuy'
  AND template_url = 'https://www.lovegobuy.com/product?shop_type=weidian&id={PRODUCT_ID}'
  AND is_deleted = FALSE;
