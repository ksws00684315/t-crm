# 任务 17b（后端）：兼容客户脚本的对外复制接口与微店复制商品

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：
> - 本机执行时，独立工作树 `.worktrees/17b-copy-api`，分支 `task/17b-copy-api` 已从最新的 `main` 检出；
> - 非本机执行时，自行 `gh repo clone ksws00684315/t-crm`，从最新的 `origin/main` 检出分支 `task/17b-copy-api`。
>
> **CI 说明**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。验收以本地全量检查为准：在本机实际跑完 `mvn verify -Pdocker-it`（需要 Docker），把命令和结果（测试数量、失败数）贴进 PR 描述和执行报告。
>
> **并行说明**：任务 26（推广表上传）可能尚未合并。它把 `crm_kol_item.product_id` 改为可空，并新增 `source`（`IMPORT` / `SHEET_UPLOAD` / `COPY`）。合并顺序在 26 之后；如果开工时 26 未合并，等它合并后把最新 `main` 合入本分支再提交 PR。迁移版本号使用 `V202610181400`～`V202610181409`。

---

## 1. 背景

客户的 Google 表格脚本（`docs/00-需求/客户补充资料-1015/prod5(2).zip` 中的 `3.0.5.gs`，「一键替换链接(V2)」）现在调用旧系统 `w2collab.com/admin-api/external/product-links/*`：

1. 提交选中的源商品 ID 与复制令牌；
2. 旧系统建任务、返回任务编号；
3. 旧系统在微店为该达人复制商品；
4. 脚本按任务编号分批取回复制后的商品 ID，拼出新链接写回表格。

本系统实现同样的 5 个接口。客户只需把脚本属性 `TALENT_CRM_API_BASE_URL` 改为 `https://admin.<域名>/admin-api`，**脚本代码不改**。复制令牌已由任务 17a 实现（`kol.api.CopyTokenApi`）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`。
2. **ADR-019 全文**（2026-10-18 第 4 条定稿），以及 ADR-012、ADR-013、ADR-016、ADR-021。
3. 设计说明书 §4.3、§4.4、§6.5（对外接口与错误码）、§5.2 `crm_shop.copy_target`、`crm_kol_item`。
4. **客户脚本**：解压 `prod5(2).zip` 到仓库外的临时目录，只读、不执行。逐个读懂这些函数发出的请求与对响应的解析：`createProductLinkConvertJob_`、`resolveProductLinkBatch_`、`fetchProductLinkJob_`、`fetchProductLinkJobItems_`、`unwrapEasyApiResponse_`、`productLinkSuccessfulResults_`、`collectProductLinkReplacementTargets_`。**契约以脚本实际发出的请求与校验为准。**
5. 现有代码：
   - `kol.api.CopyTokenApi`（17a）；
   - `integration.api.WeidianBusinessApi`：`item`、`itemDetail`、`addItem`、`saveItemDetail`；令牌中心、限流、网关重试（09a）；
   - `product` 的原商品与店铺服务、`SheetLinkParser`；
   - `link` 模块的链接索引登记（ADR-013）与 `linkitem` 桶（任务 11b、26）；
   - `shared/ApiCatalogConsistencyTest`。

## 3. 已定的设计决定（不要自行改变）

### 3.1 五个接口（挂在 `/admin-api/external/product-links/**`）

| 方法与路径 | 认证 | 说明 |
|---|---|---|
| `GET /templates?channelCode=WEIDIAN` | 无 | 链接模板：第一项固定为「T-CRM 中转」（`templateUrl = {crm.link.base-url}/i/{PRODUCT_ID}`），其后为各公司 `crm_link_template` 合并去重的代购平台模板。字段 `id`、`templateCode`、`templateName`、`templateUrl`、`channelCode`、`sortOrder` |
| `POST /convert` | 请求体 `token` | `{token, callerPlatform, items:[{sourceChannelCode, sourceProductId, ruleId}]}`，最多 500 条；建任务后**立即**返回 `{jobCode}` |
| `POST /jobs/{jobCode}/items/resolve` | 请求体 `token`，必须属于该任务的达人 | 请求 `{token, items:[{sourceChannelCode, sourceProductId}]}`，最多 50 条；响应 `{jobCode, status, resultVersion, items}` |
| `GET /jobs/{jobCode}` | 任务编号 | `status`（`processing` / `completed` / `failed` / `partial_failed`）、`totalCount`、`successCount`、`failCount` |
| `GET /jobs/{jobCode}/items` | 任务编号 | 明细数组：`sourceChannelCode`、`sourceProductId`、`status`、`targetChannelCode`（`WEIDIAN`）、`targetExternalProductId`、`targetStoreId`、`errorMessage` |

- 这些接口没有管理端登录态，声明为公开端点（`@PublicEndpoint`），并在 `SecurityConfiguration` 中排除；`X-Company-Id` 核对不适用。
- 统一信封 `{code:0, data}`。ID 一律为字符串，状态值用小写（`success`、`completed` 等），字段名严格照旧，新增字段只能追加。
- `msg` 用中文（§6.5 的例外，脚本会直接显示给运营）。
- 错误码：令牌无效 401 `COPY_TOKEN_INVALID`、过期 401 `COPY_TOKEN_EXPIRED`、额度不足 429 `COPY_TOKEN_QUOTA_EXCEEDED`、达人停用 403 `KOL_DISABLED`、没有目标店铺 422 `COPY_TARGET_SHOP_MISSING`（新增）、任务编号不存在 404。
- 限流：5 个接口按来源 IP 限流；`convert` 和 `items/resolve` 另按令牌限流（Redis 计数，限值写在配置中）。
- 任务编号：128 位随机值（`SecureRandom`，URL 安全编码），不可猜测。

### 3.2 数据（迁移 `V202610181400` 起）

- `crm_copy_job`：`tenant_id`、`kol_id`、`token_id`、`job_code`（唯一）、`caller_platform`、`target_shop_id`、`status`、`total_count`、`success_count`、`fail_count`、`create_time`、`update_time`。
- `crm_copy_job_item`：`job_id`、`tenant_id`、`source_channel`、`source_product_id`、`rule_id`、`status`（`pending` / `success` / `failed`）、`target_item_id`、`error_message`（中文，不含敏感信息）、`update_time`；唯一 `(job_id, source_product_id)`（同一任务内去重）。
- `crm_shop.copy_target`：如尚未有该列则新增（`BOOLEAN NOT NULL DEFAULT FALSE`），并加部分唯一索引 `(tenant_id) WHERE copy_target AND is_deleted = FALSE`，保证每家公司最多一个默认复制店铺。
- 表和字段都有中文注释。

### 3.3 复制流程（ADR-019 第 4 条）

`convert` 只做校验和建任务：

1. `CopyTokenApi.authenticate(token)` 得到公司、达人、目标店铺；
2. 目标店铺：令牌指定的店铺；否则本公司的默认复制店铺；都没有时返回 422 `COPY_TARGET_SHOP_MISSING`。目标店铺必须启用并已完成微店授权；
3. 只接受 `sourceChannelCode = WEIDIAN`，其他渠道的条目直接记为 `failed`（「不支持的来源渠道」）；
4. 写入任务与明细，提交给后台工人，返回 `jobCode`。

后台工人在任务所属公司的作用域中逐条处理：

1. **找来源商品**：在本公司启用的店铺中按微店商品 ID 找到来源商品，必要时登记或更新为原商品。找不到时该条 `failed`（「来源商品不在本公司已登记的店铺中」）；在多个店铺中都能找到时该条 `failed`，`errorMessage` 写明「多个店铺都有该商品」，`items/resolve` 中状态为 `conflict`。
2. **已复制过**：该达人已有这件原商品的有效对应关系时，直接记为 `success`，返回已有的复制商品 ID，不扣额度、不重复复制。
3. **已替换过**：来源 ID 本身就是该达人已有的复制商品 ID 时，`items/resolve` 返回 `already_applied`。
4. **扣额度**：真正复制前调用 `CopyTokenApi.consume(tokenId, 1)`；不足时该条 `failed`（「额度不足」）。
5. **复制**：`vdian.item.get` 与 `vdian.item.getItemDetail` 读取来源 → `vdian.item.add` 在目标店铺新增 → `vdian.item.saveItemDetail` 保存详情。**不加价**，照原价复制；脚本传来的加价参数忽略。
6. **写对应关系**：写入 `crm_kol_item`（`source = COPY`，`product_id` 为原商品），并登记链接索引刷新（ADR-013，含 `linkitem` 桶）。
7. **失败**：任一步失败时该条 `failed`，`errorMessage` 写简短的中文原因（微店错误码可以附在后面，不得包含令牌或密钥）。**不做业务层重试**；网关对临时网络错误的自动重试保留。
8. 全部处理完后，任务状态为 `completed`（全部成功）、`partial_failed` 或 `failed`。

工人用有界线程池；同一任务内顺序处理；遵守 09a 的限流；应用重启后，未完成的任务在启动时继续处理（或标为失败并写明原因，二选一，写进报告）。

### 3.4 `items/resolve` 的状态（ADR-019 第 2 条）

- 本批请求的各条都已处理完时 `status = ready`，否则 `job_pending`；`preparing` 不使用。
- 每条明细：`success`（带 `targetExternalProductId`、`targetStoreId`、`targetChannelCode`）/ `failed` / `not_found`（不在本任务中）/ `conflict` / `already_applied`。
- `resultVersion`：任务最近一次更新的时间戳（毫秒）。
- 响应中的 `items` 与请求一一对应、顺序相同、条数相同。脚本会校验这一点。

### 3.5 管理端：默认复制店铺

- 店铺管理（超管）增加「设为默认复制店铺」操作：设置一个时，取消同公司原来的那个；列表显示标记。
- 生成复制令牌的对话框（17a）中，目标店铺下拉的「自动分配」改为「默认复制店铺（{店铺名}）」；没有设置默认店铺时给出提示。
- 只改这两处管理端页面，以及语言包。

## 4. 测试要求

| 场景 | 期望 |
|---|---|
| **契约** | 用 `3.0.5.gs` 实际发出的请求体（`callerPlatform: "google_sheet_plugin"`、批内去重、最多 50 条）和它对响应的解析规则做契约测试：字段名、状态值、条数与顺序 |
| 认证 | 无效、过期、额度不足、达人停用、`items/resolve` 用其他达人的令牌 |
| 目标店铺 | 令牌指定、默认店铺、都没有时 422；店铺未授权或停用 |
| 复制 | 成功写对应关系与链接索引；已有对应关系不重复复制、不扣额度；`already_applied`；`conflict`；来源找不到；额度在复制中途用尽 |
| 失败 | 微店返回错误时该条 `failed`、带原因、不重试；任务最终状态正确 |
| 任务编号 | 不可猜测（长度与字符集）；不存在时 404；查询结果只有商品 ID |
| 限流 | 按 IP、按令牌 |
| 隔离 | 任务和明细按公司隔离；系统作用域只用于按令牌和任务编号定位 |
| 集成测试 | 用本地桩模拟微店接口（参照 09a、13 的做法），走完 `convert` → 后台处理 → `items/resolve` → 跳转服务 `/i/{复制商品 ID}` 可跳转 |
| 接口清单 | `ApiCatalogConsistencyTest` 通过 |

## 5. 文档与报告

- 设计说明书：§6.5 实际字段与错误码、§5.2 两张新表与 `copy_target`、§6.2 / §6.3 标记区间登记新接口。
- `deploy/README.md`：客户切换脚本的步骤（修改脚本属性 `TALENT_CRM_API_BASE_URL`），以及 Nginx 对 `/admin-api/external/` 的限流。
- 执行报告 `docs/04-开发任务/17b-对外复制接口-执行报告.md`：接口与示例、复制流程、重启后的处理方式、契约测试清单、偏离与遗留。

## 6. 禁止事项与停止条件

**禁止**：修改客户脚本；改变 5 个接口的路径、字段名、状态值；在日志、错误信息、响应中出现令牌明文或微店密钥；业务层自动重试；加价；新增依赖；修改已合并的迁移脚本；删除或弱化测试；推送 `main`、合并 PR；`push --force`、`reset --hard`、改写已推送历史。

**停下来报告**（推送已完成的提交，以 `[WIP]` 标题创建 PR）：脚本的实际请求与 ADR-019 的描述不一致；微店接口在 09a 中的定义无法完成复制；合理排查后验证仍失败。

完成后回复一段总结：PR 链接、做了什么、本地测试结果、遗留。
