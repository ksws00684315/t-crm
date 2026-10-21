# 任务 38：商品任务页（后端 + 管理端，1018 表第 28～31 行）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/38-copy-jobs`，分支 `task/38-copy-jobs`，已从最新 `main` 检出。后端端口 `TCRM_ADMIN_PORT=18381`，管理端 Vite `8838`。迁移版本号只用 `V202610221100`～`V202610221119`。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，其中的工作目录、共享中间件、子代理检查、交付规则全部适用。

## 1. 需求原文

进度表「功能评估-P0」第 28～31 行（06.11～06.14），实现前逐字阅读：

| 行 | 编号 | 功能说明要点 |
|---|---|---|
| 28 | 06.11 | 商务用令牌在现有 Google 表格脚本中发起复制转换后，系统能查询对应商品任务及处理结果；令牌关联达人、目标渠道及店铺，再关联任务中生成的商品 ID，供后续销售归属使用 |
| 29 | 06.12 | 按任务编号、目标渠道、状态查询，重置、刷新、分页、任务编号复制；列表展示任务编号、目标渠道、状态、结果汇总、错误原因、创建时间、完成时间及「查看明细」 |
| 30 | 06.13 | 展示总数量、成功数量、失败数量及任务级错误原因；「已完成」任务仍可能包含失败项，应结合明细确认 |
| 31 | 06.14 | 明细展示序号、来源渠道、旧商品 ID、来源店铺、来源商户号、目标渠道、目标店铺、新商品 ID、新商品链接、状态和失败原因；旧商品 ID 复制、新商品链接打开 |

布局参考第 29（任务列表）、31（任务明细弹窗）行的内嵌截图：结果列显示「总 N｜成功 N｜失败 N」三个标签；明细中来源店铺、目标店铺显示名称并在下方小字显示店铺 ID；新商品链接显示「打开」。

## 2. 现状

- 任务 17b 的对外复制接口已经在建任务：`crm_copy_job`（`job_code`、`kol_id`、`token_id`、`caller_platform`、`target_shop_id`、`status`：`processing` / `completed` / `failed` / `partial_failed`、`total_count`、`success_count`、`fail_count`、`create_time`、`update_time`）与 `crm_copy_job_item`（`source_channel`、`source_product_id`、`rule_id`、`status`、`target_item_id`、`error_message`）。复制成功时同时建立达人商品对应关系（`crm_kol_item`）。
- 缺：管理端页面（菜单「商品管理—商品任务」现在是「开发中」占位）、列表与明细接口、目标渠道、任务级错误原因、完成时间、明细的来源店铺与来源商户号、新商品链接。

## 3. 要做的事

### 3.1 后端

- 迁移补充：
  - `crm_copy_job`：`target_channel`（按令牌或目标店铺回填，目前都是 `WEIDIAN`）、`error_message`（任务级错误，例如容量不足、店铺未授权、令牌失效）、`finish_time`（任务进入终态的时间；已有终态任务用 `update_time` 回填）；
  - `crm_copy_job_item`：`source_shop_id`（来源店铺，复制时按来源商品所属店铺记录；已有数据按原商品回填，查不到的留空）。
  - 写入点：在任务 17b 的复制流程中同步写这些字段；任务级失败时写 `error_message`。
- 列表 `GET /admin-api/product/copy-jobs`（`product:view`）：参数 `jobCode`（精确或前缀）、`targetChannel`、`status`、`page`、`size`；按创建时间倒序。每项：`id`、`jobCode`、`targetChannel`、`status`、`totalCount`、`successCount`、`failCount`、`errorMessage`、`createTime`、`finishTime`，另附 `kolId`、`kolCode`、`kolName`、`targetShopCode`、`targetShopName`（便于核对，列表可在列设置中默认隐藏）。
- 明细 `GET /admin-api/product/copy-jobs/{id}/items`（`product:view`，分页）：`seq`（按明细创建顺序从 1 编号）、`sourceChannel`、`sourceProductId`、`sourceShopId`、`sourceShopName`、`sourceMerchantNo`（来源店铺的微店店铺标识）、`targetChannel`、`targetShopId`、`targetShopName`、`targetItemId`、`targetItemUrl`（`https://weidian.com/item.html?itemID={新商品ID}`，统一在后端拼接）、`status`、`errorMessage`。
- 数据范围：商务（`OWN`）只看自己负责的达人的任务；越权按 ID 访问返回 404。
- 状态字典写进设计说明书：任务状态与明细状态各自的取值和中文含义。

### 3.2 管理端（`views/product/copy-job/`，替换「商品任务」占位路由）

- 筛选：任务编号、目标渠道（下拉）、状态（下拉：处理中、已完成、部分失败、失败）；查询、重置、刷新。
- 列表列：任务编号（可复制）、目标渠道、状态、结果（「总 / 成功 / 失败」三个标签，失败数大于 0 标红）、错误原因（无则「-」，长文本悬停看全文）、创建时间、完成时间、操作「查看明细」。
- 状态为「已完成」但失败数大于 0 时，在结果列旁提示「含失败项，请查看明细」（06.13）。
- 明细弹窗，标题「任务明细：{任务编号}」，表格内横向滚动：序号、来源渠道、旧商品 ID（可复制）、来源店铺（名称 + 小字店铺 ID）、来源商户号、目标渠道、目标店铺（名称 + 小字店铺 ID）、新商品 ID、新商品链接（「打开」，新窗口）、状态、失败原因。明细分页。

## 4. 测试重点

- 后端：列表筛选与分页、商务数据范围、公司隔离、明细序号与链接拼接、回填迁移、复制流程写入任务级错误与完成时间、来源店铺记录。
- 前端：列与筛选参数、结果标签与「含失败项」提示、明细弹窗字段、复制与打开、中英文。

## 5. 浏览器验证

用 `t38_` 前缀在共享库造数据：用测试令牌调用对外复制接口建任务（微店未授权时任务会失败，正好覆盖「失败」「任务级错误原因」）；另外直接插入少量 `t38_` 测试任务覆盖「已完成含失败项」「部分失败」（收尾删除）。按通用要求矩阵截图，角色至少覆盖公司超级管理员和商务。

## 6. 不做

达人商品页（任务 39）；复制流程本身的改动（只补写字段）；按 Google 表格自动发起复制（P2）。
