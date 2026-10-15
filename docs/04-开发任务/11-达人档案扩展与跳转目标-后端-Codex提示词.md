# 任务 11（后端）：达人档案扩展、链接模板与跳转目标

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 10a（可配置权限）、12a（达人账号）同时进行。三个任务都会改 `kol` 模块，请尽量只在必要处改动。
> - 预计 10a 先合并。10a 合并后，若本任务尚未合并，请 rebase 到最新 `main`，并把本任务新接口的角色注解改为第 4.6 节写明的权限码。
> - 迁移脚本版本号固定为 `V202610171100`，不要改。

---

## 1. 背景

客户 2026-10-14 会议与 10-15 需求补充确认了三点：

- 每位达人都有自己的 WhatsApp、主阵地、Google 推广表格地址，以及一个代购平台（Agent）和在该平台上的邀请码；
- 推广链接最终打开的是该达人代购平台上的商品页，例如 `https://item.kakobuy.com/item/details?url=...&affcode={邀请码}`；
- 客户的表格脚本会写入形如 `https://t.域名/i/{复制商品 ID}` 的中转链接（ADR-019）。

负责人据此决定：

- 达人档案新增上述字段；
- 新增**链接模板**表，维护各代购平台的链接模板；
- 中转链接的跳转目标从「微店商品页」改为「达人代购平台模板 + 邀请码 + 专属商品 ID」；
- 链接索引新增按专属商品 ID 查找的 `linkitem` 哈希，供跳转服务的 `/i/{id}` 使用。跳转服务本身由任务 11b 修改，不在本任务内。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`。
2. 设计说明书：
   - §0.3 术语（代购平台、邀请码、链接模板）；
   - §4.4（链接与推广表格）；
   - §5.2：`crm_kol` 的「2026-10-16 修订新增或调整」表、`crm_link_template`；
   - §7.4（跳转服务与 Redis 链接索引）。
3. [ADR-019](../06-架构决策/ADR-019-兼容客户表格脚本的商品复制接口.md) 第 3、5、6 条；ADR-012、ADR-013。
4. 现有代码：
   - `kol` 模块：`KolController`、`CreateKolRequest`、`EditKolRequest`、`KolVO`、`KolDetailVO`、`KolService`、`KolWriter`、`KolImportColumn`、`KolImportProcessor`、`KolRules`、`SocialAccountsJson`；
   - `link` 模块：`LinkUrls`、`LinkRules`、`LinkIndexService`、`LinkIndexStore`、`LinkRebuildService`、`LinkService`、`LinkIndexListener`；
   - `kol.api` 中的事件。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/11-kol-profile-link-target` 已从最新的 `main` 检出。
- **只修改 `backend/`、你的执行报告，以及第 4.7 节列出的设计说明书段落。**
- JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。
- 推送分支并按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610171100__extend_kol_profile_and_link_template.sql`

1. `crm_kol` 新增列：

   | 列 | 类型与约束 |
   |---|---|
   | `whatsapp` | `VARCHAR(32) NULL`，`CHECK (whatsapp ~ '^\+[0-9]{8,15}$')` |
   | `link_template_id` | `BIGINT NULL` |
   | `invite_code` | `VARCHAR(64) NULL`，`CHECK (invite_code ~ '^[A-Za-z0-9_-]{1,64}$')` |
   | `sheet_url` | `VARCHAR(512) NULL`，`CHECK (sheet_url LIKE 'https://docs.google.com/spreadsheets/%')` |
   | `register_source` | `SMALLINT NOT NULL DEFAULT 1`，`CHECK (register_source IN (1, 2))`，1 后台录入、2 自助注册 |

   另外 `country` 改为可空，保留「两位大写字母」的 `CHECK`（空值放行）。所有新列都要有中文注释。
2. 新表 `crm_link_template`：
   - 字段：`id`、`tenant_id NOT NULL`、`template_code VARCHAR(32)`（`^[a-z0-9-]{2,32}$`）、`template_name VARCHAR(64)`、`template_url VARCHAR(512)`、`channel_code VARCHAR(16) NOT NULL DEFAULT 'WEIDIAN'`、`sort_order INT NOT NULL DEFAULT 0`、`status SMALLINT`（1 启用 / 0 停用），以及通用字段（含 `is_deleted`）；
   - 唯一索引 `(tenant_id, template_code) WHERE is_deleted = FALSE`；
   - `CHECK`：`template_url` 以 `https://` 开头且包含 `{PRODUCT_ID}`。
3. 不设外键，与现有表的做法一致。

### 4.2 链接模板管理

| 接口 | 权限 | 说明 |
|---|---|---|
| `GET /admin-api/link/templates` | 与达人编辑相同 | 本公司全部模板，按 `sort_order`、`id` 排序，不分页 |
| `POST /admin-api/link/templates` | 超管 | 新建 |
| `PUT /admin-api/link/templates/{id}` | 超管 | 修改名称、地址、排序（编码不可改） |
| `PUT /admin-api/link/templates/{id}/status` | 超管 | 启用 / 停用 |

模板只能停用，不能删除。停用或修改地址后，按 4.4 第 4 条登记刷新。

### 4.3 达人字段

1. 新建、编辑、详情、列表的请求与响应增加：
   - `whatsapp`、`linkTemplateId`、`inviteCode`、`sheetUrl`；
   - 响应另有 `linkTemplateName`、`registerSource`。

   `country` 在新建、编辑时都改为可选。
2. 社媒账号 JSON 增加 `primary`（布尔，默认 `false`）。一位达人最多一个主阵地；多于一个返回 400。后台录入不强制要求主阵地。
3. 校验：
   - `whatsapp` 的格式与数据库 `CHECK` 一致；
   - `linkTemplateId` 必须是本公司启用的模板，否则返回 400 `LINK_TEMPLATE_INVALID`；
   - `inviteCode`、`sheetUrl` 的格式与 `CHECK` 一致。

   新错误码登记到 `shared.ErrorCode`，中英文文案写入后端语言文件。前端映射由管理端任务补，在报告中列出。
4. Excel 导入模板在末尾追加四列：`WhatsApp`、`代购平台编码`（模板编码）、`邀请码`、`推广表格地址`，中英文表头都要支持；旧模板（不含这四列）的文件仍能导入。编号、商务等原有规则不变。
5. 达人列表的关键字搜索不扩展到新字段。

### 4.4 跳转目标与链接索引

1. **目标地址规则**，集中在 `LinkUrls` 一处：
   - 达人设置了**启用的**模板，且（模板不含 `{INVITE_CODE}`，或达人有邀请码）时：把 `{INVITE_CODE}` 替换为 URL 编码后的邀请码，`{PRODUCT_ID}` 替换为专属商品 ID；
   - 否则为 `https://weidian.com/item.html?itemID={专属商品 ID}`。

   `LinkRules.indexValue` 与链接列表接口的「目标地址」都用这条规则。
2. **`linkitem` 索引**：
   - 结构为 `HASH linkitem:{nn}`，`nn` 是专属商品 ID 的末两位数字（不足两位前补 0），field 为专属商品 ID，value 与 `link:{kolCode}` 中对应字段完全相同的 JSON；
   - `LinkIndexService` 在同一把达人锁内，与 `link:{kolCode}` 同步写入和删除：`refreshMapping` 只改对应的一个 field；`refreshKol` 先删除该达人在旧 `link:{kolCode}` 中出现过的专属商品 ID，再写入当前值；
   - 映射失效且无新映射时，两处都删除。
3. **全量重建**：`link:{kolCode}` 的做法不变。`linkitem` 不能整桶替换（桶是多位达人共用的），改为：
   1. 逐个达人在锁内把当前字段写入 `linkitem` 桶（覆盖写）；
   2. 收集全部有效的专属商品 ID；
   3. 全部达人处理完后，扫描 100 个桶，删除不在集合中的 field，**每个待删 field 删除前回查数据库**确认已没有有效映射，避免误删重建期间新增的映射。

   重建结果 `RebuildResult` 增加 `itemFields`、`orphanItemFields`。
4. **刷新登记**（ADR-013 的 outbox）：
   - 达人的 `link_template_id` 或 `invite_code` 变化，登记该达人的 `KOL` 范围；
   - 模板的地址或状态变化，按页查询使用该模板的达人（每页 200），逐个登记 `KOL` 范围；
   - 都与业务变更在同一事务内完成。新增的事件定义在发布方的 `api` 包。
5. Redis 内存监控不变；在执行报告中给出 200 万条映射时两类哈希的实测内存。

### 4.5 不在本任务范围

- 跳转服务的 `/i/{id}` 路由（任务 11b）。
- 达人自助注册与达人账号（任务 12a / 12c）。
- 管理端界面（任务 11c）。
- 复制令牌、对外复制接口（任务 17）。

### 4.6 权限注解

本任务开始时 10a 尚未合并，先沿用现有写法：

- 读取模板：与达人编辑接口相同的角色；
- 写入模板：`SUPER_ADMIN`。

10a 合并后，若本任务尚未合并，rebase 并改为：

- 读取模板：`@SaCheckPermission("kol:edit")`；
- 写入模板：`@SaCheckRole("SUPER_ADMIN")`；
- 达人接口沿用 10a 的结果。

### 4.7 文档

- 设计说明书 §7.4「按复制商品 ID 的索引」一条中，全量重建一句改为 4.4 第 3 条的做法（覆盖写 + 回查后删除），不要写「临时 key + RENAME」。
- §6.2 增加链接模板的四个接口，删除「待实现」字样，与实际实现一致。

## 5. 执行步骤

1. 迁移脚本。
2. 达人字段：请求、响应、校验、导入。
3. 链接模板管理接口。
4. 目标地址规则与 `linkitem` 索引的增量写入。
5. 全量重建与刷新登记。
6. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 目标地址 | 有模板有邀请码、模板不含 `{INVITE_CODE}`、模板含 `{INVITE_CODE}` 但无邀请码、模板停用、无模板，五种情况的地址正确；邀请码中的特殊字符做 URL 编码 |
| 增量写入 | 新增、更换、失效映射后，`link:{kolCode}` 与 `linkitem:{nn}` 同时正确 |
| 达人刷新 | 修改模板或邀请码后数秒内两类哈希中的 `targetUrl` 更新 |
| 模板变更 | 修改模板地址后，使用它的全部达人都被登记并刷新 |
| 全量重建 | 删除 Redis 后重建，两类哈希与数据库一致；多余 field 被删除；重建期间新增的映射不被误删 |
| 字段校验 | WhatsApp、邀请码、表格地址格式；主阵地最多一个；模板不属于本公司或已停用返回 400 |
| 导入 | 新模板四列可导入；旧模板仍可导入；中英文表头 |
| 公司隔离 | 另一家公司的模板不可见、不可用 |
| 现有行为 | 原有跳转、链接列表、点击统计的测试全部通过 |

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/11-达人档案扩展与跳转目标-后端-执行报告.md`，内容包括：

- 迁移内容；
- 接口变化，供 11c 管理端任务使用，列出请求与响应字段；
- 目标地址规则与 Redis 结构；
- 重建做法；
- 新错误码；
- 内存实测；
- 测试清单；
- 偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 修改 `redirect/`、`admin-ui/`、`kol-portal/`、`deploy/`；
- 改变 `link:{kolCode}` 的 key 与 value 格式（只改 `targetUrl` 的取值）；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 与 10a、12a 的改动发生无法自行判断的冲突；
- 设计说明书、ADR 与本提示词冲突；
- 验证在合理排查后仍失败。
