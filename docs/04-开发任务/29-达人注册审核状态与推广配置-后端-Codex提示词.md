# 任务 29（后端）：达人注册、审核、三种状态、推广配置与清理（1018 表第 7～15 行）

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：非本机执行。自行 `gh repo clone ksws00684315/t-crm`，从最新的 `origin/main` 检出分支 `task/29-kol-lifecycle-backend`。需要 JDK 21、Docker。本任务只改后端与文档，不需要起前端和本地预览。
>
> **契约补充（2026-10-19）**：3.6 清理明细增加 `shopName`；3.7 注册预览的响应字段明确为 `{bdUserName, tenantName}`。
>
> **并行说明**：前端任务 30（管理端 + 达人端，本机执行）按本文**第 3 节的接口约定**同时开发。第 3 节是双方的契约：字段名、取值、错误码、路径都不要擅自改。确实需要改时，先停下来，在 PR 中说明。迁移版本号使用 `V202610191000`～`V202610191009`。
>
> **CI 说明**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。验收以本地全量检查为准，命令与结果写进 PR 描述和执行报告。

---

## 1. 目标与必读

完整实现客户 1018 表（`docs/04-开发任务/CRM系统功能评估与进度跟踪表-1018.xlsx`，工作表「功能评估-P0」）第 7～15 行的后端部分：01.01、02.01、02.02、02.03、03.01、03.02、04.01、04.02、04.03。其中 02.02、02.03、03.02 已经验证通过，这次保持不变、不退化即可。

必读：

1. `AGENTS.md`、`docs/05-工程规范/`（全部）。
2. 1018 表第 7～15 行原文（可以用 openpyxl 读取）；`docs/04-开发任务/P0验证与菜单问题-1018.md`。
3. ADR-023（本任务修订其中的状态、推广配置和清理三部分）、ADR-019、ADR-013、ADR-022；设计说明书 §4.2、§5.2、§6.2、§6.3。
4. 现有代码：`kol` 模块（`KolService`、`KolPromotionService`、`KolPromotionSupport`、`KolSelfRegisterService`、`KolRegisterCodeService`、`KolClaimService`、`KolCleanupService`、`KolAuthService`）、`order` 模块的达人清理编排（`IdleKolCleanupJob` / `IdleKolCleanupService`，经 `KolCleanupApi` 与 `KolItemApi` 编排）、`product.api.WeidianItemCopyApi`（17b，由 `integration` 实现）、`integration.api.WeidianBusinessApi#deleteItem`、`link` 模块的链接索引刷新。

## 2. 负责人已定的决定（2026-10-19，不要自行改变）

| 项 | 决定 |
|---|---|
| 达人编号 | 保留 `KOL-` + 3 位随机大写字母 + 序号（如 `KOL-ASD001`），**不改** |
| 状态 | 按 1018 拆成三种：审核状态、登录状态、关系状态（见 3.1） |
| 清理 | 清理时调用微店接口删除达人的专属商品，逐条记录结果（见 3.6） |
| 代购平台地址 | 商务为选中的**每个**代购平台各填一个达人专属地址；它取代现在单一的「落地页地址」`landing_url`（见 3.4） |

需要新建 **ADR-025**「达人三种状态、推广配置版本与清理删除微店商品」：写明上表的决定，以及它替代了 ADR-023 的哪几条。ADR-023 顶部加一行「部分被 ADR-025 取代」。

## 3. 接口与数据约定（前后端契约）

### 3.1 三种状态

`crm_kol` 新增 `review_status`、`relation_status`，**删除** `promotion_status`。迁移时按下表映射已有数据：

| 原 `promotion_status` | `review_status` | `relation_status` |
|---|---|---|
| `PENDING_REVIEW` | `PENDING` | `PENDING_PROMOTION` |
| `PENDING_PROMOTION` | `APPROVED` | `PENDING_PROMOTION` |
| `PROMOTING` | `APPROVED` | `PROMOTING` |
| `ENDED` | `APPROVED` | `INVALID` |

| 状态 | 取值 | 规则 |
|---|---|---|
| 审核状态 `reviewStatus` | `PENDING` 待审核 / `APPROVED` 已通过 | 注册后为 `PENDING`。商务在审核页保存后变为 `APPROVED`。**不可再次审核**：已通过时调用审核接口返回 409 `KOL_REVIEW_ALREADY_DONE` |
| 关系状态 `relationStatus`（存储值） | `PENDING_PROMOTION` 待推广 / `PROMOTING` 推广中 / `INVALID` 无效 | 注册后为 `PENDING_PROMOTION`。改为 `PROMOTING` 要求审核已通过、推广配置齐全；`INVALID` 由商务手动设置；`INVALID` 可以改回 `PENDING_PROMOTION` |
| 显示用关系状态 `displayRelationStatus` | 上面三个值，再加 `PENDING_UPDATE` 待更新 | 存储值为 `INVALID` 时显示 `INVALID`；否则，没有可用商务时显示 `PENDING_UPDATE`；其余情况显示存储值 |
| 登录状态 `loginStatus`（不存储，按规则推导） | `NORMAL` 正常 / `FROZEN` 冻结 / `ABNORMAL` 异常 | 账号停用（现有 `status = 0`）→ `FROZEN`；否则，账号未激活（`accountStatus = 0`），或因连续登录失败正被锁定（`kolLoginFailures`）→ `ABNORMAL`；否则 → `NORMAL` |

- 冻结 / 解冻：沿用现有的 `PUT /admin-api/kol/kols/{id}/status`（`0` 冻结，`1` 解冻），语义不变，前端改用「冻结 / 解冻」的说法。
- 登录状态里的「锁定」要从登录失败计数器读取。列表每页最多 100 条，按页批量查询，不要逐条请求 Redis。
- 「待更新」期间的业绩归管理员，规则不变（设计 §4.13）。

### 3.2 达人列表与详情（04.01、03.02）

`GET /admin-api/kol/kols` 和 `GET /admin-api/kol/kols/{id}`：

- **删除**字段：`promotionStatus`、`displayStatus`、`landingUrl`、`platformTemplateIds`。
- **新增**字段：
  - `reviewStatus`、`relationStatus`、`displayRelationStatus`、`loginStatus`；
  - `phone`：手机号，E.164 格式，可以为空；
  - `platforms`：`[{templateId, templateName, address}]`；
  - `jumpTemplateId`；
  - `configVersion`：当前推广配置的版本号，整数，还没配置过时为 `null`；
  - `inviteTime`：邀请时间，即达人注册时间，等于现在的 `createTime`；
  - `bdUserName`：邀请人，即当前绑定的商务。
- **筛选参数**：
  - `keyword`：编号 / 姓名 / 邮箱，保持现状；
  - `reviewStatus`；
  - `relationStatus`：按**显示用**关系状态筛选，可取四个值，包括 `PENDING_UPDATE`；
  - `loginStatus`：只支持 `FROZEN` 和 `NORMAL_OR_ABNORMAL`（即未冻结）。「异常」依赖 Redis，不能在 SQL 里筛选；
  - 删除原来的 `displayStatus` 参数。
- 商务只能看到本人名下的达人；未绑定的达人只能精确搜索后认领（03.02），保持现状。

### 3.3 审核（04.02）

新接口 `POST /admin-api/kol/kols/{id}/review`，权限 `kol:edit`，数据范围同编辑：

- 请求体：`{name, phone, whatsapp, primaryPlatform, primaryHandle, country, remark}`。
  - `name` 必填；其余可以为空；
  - `phone`、`whatsapp` 都是 E.164 格式（含国际区号）；
  - `primaryPlatform` 取值同注册接口；`primaryHandle` 是核心社媒 ID；
  - 邮箱和达人编号不在请求体里，只读。
- 只有 `reviewStatus = PENDING` 时才能调用：保存资料，并把审核状态设为 `APPROVED`；已经通过的返回 409 `KOL_REVIEW_ALREADY_DONE`。
- 写一条推广记录，类型 `REVIEW`，前后快照都要包含资料字段。
- 普通编辑 `PUT /admin-api/kol/kols/{id}` 保持任务 28 的规则：资料类字段可改，邮箱、编号、佣金、邀请码等不能改；新增 `phone` 字段。
- 自动绑定商务（按注册码）保持现状；自动绑定后**仍须经过审核**。
- 删除原来「改推广状态为 `PENDING_PROMOTION` 表示审核通过」的写法。审核只走这个新接口。

### 3.4 推广配置（04.03）

`GET` / `PUT /admin-api/kol/kols/{id}/promotion-config`：

- 只有审核已通过的达人才能保存推广配置，否则返回 409，新增错误码 `KOL_REVIEW_REQUIRED`。
- 请求体：
  ```json
  {
    "defaultRate": "0.1550",
    "inviteCode": "ABC123",
    "platforms": [{"templateId": "7", "address": "https://..."}],
    "jumpTemplateId": "7",
    "sheetUrl": "https://docs.google.com/...",
    "remark": "..."
  }
  ```
- 必填项：佣金比例、达人邀请码（代购平台的邀请码，不是商务邀请码）、至少一个代购平台、每个平台的地址（https）、推广表格地址。备注选填。跳转平台规则沿用任务 28：只选一个平台时自动取它，选了多个平台时必须从中指定一个。
- 存储：
  - `crm_kol_platform` 新增 `address` 列；
  - 删除 `crm_kol.landing_url`。迁移时，把已有达人的 `landing_url` 复制到该达人每个平台行的 `address`；
  - 平台可以增删，平台清单仍由「代购平台模板」维护。
- **版本**：
  - 每次保存生成一个新版本：`crm_kol_promotion_record` 新增 `version_no`（每个达人从 1 开始递增）和 `effective_time`（等于保存时间）；
  - `crm_kol` 记录当前版本号；
  - `GET /promotion-records` 返回 `versionNo`、`effectiveTime`。
  - 佣金按哪个版本计算，由以后的佣金任务按「支付时间所在的版本」实现，本任务只保证版本和生效时间可追溯。
- 「改为推广中」时的缺项检查：422 `KOL_PROMOTION_CONFIG_INCOMPLETE`，`data` 是缺项字段名数组，可能的值为 `defaultRate`、`inviteCode`、`platforms`、`platformAddress`、`jumpTemplateId`、`sheetUrl`，另外审核未通过时为 `review`。
- 跳转平台或邀请码有变化时，发布 `KolLinkTargetChangedEvent`（任务 28 的规则不变）。

### 3.5 关系状态接口

- 原来的 `PUT /admin-api/kol/kols/{id}/promotion-status` 改为 `PUT /admin-api/kol/kols/{id}/relation-status`，请求体 `{status}`，取值 `PENDING_PROMOTION`、`PROMOTING`、`INVALID`。
- 「保存并开始推广」由前端依次调用推广配置接口和本接口完成。
- 每次变更写推广记录，类型 `STATUS`。
- 删除旧路径，同步修改设计 §6.2 中标记区间内的接口清单。

### 3.6 清理（04.01）

现有的清理配置（开关、天数、预览）和自动清理任务保留，在此基础上增加：

- **手动清理**：`POST /admin-api/kol/kols/{id}/cleanup`，请求体 `{reason}`（必填，最多 200 字），权限 `kol:edit`。效果与自动清理相同。
- **清理时删除微店商品**：
  - 对达人每一条有效的专属商品对应关系，生成一条清理明细，然后结束对应关系、逻辑删除达人（这部分沿用现有逻辑）；
  - 后台工人逐条调用微店删除接口：在 `WeidianItemCopyApi` 上增加删除方法，由 `integration` 用 `WeidianBusinessApi#deleteItem` 实现；
  - 微店返回「商品不存在」也视为成功；
  - 其他失败记 `FAILED` 和原因，原因用中文，不含令牌和密钥；
  - 不做业务层自动重试；提供手动重试接口，见下文。
- **清理明细表** `crm_kol_cleanup_item`，放在 `product` 模块。字段：`tenant_id`、`kol_id`、`kol_code`（快照）、`kol_item_id`、`shop_id`、`weidian_item_id`、`status`（`PENDING` / `SUCCESS` / `FAILED`）、`trigger`（`AUTO` / `MANUAL`）、`reason`、`fail_reason`、`cleanup_time`、`finish_time`。表和字段都要有注释。
- **接口**：
  - `GET /admin-api/kol/cleanup-items?kolCode&status&page&size`：列表，按数据范围过滤。每行字段：`id`、`kolCode`、`shopId`、`shopName`、`weidianItemId`、`status`、`trigger`、`reason`、`failReason`、`cleanupTime`、`finishTime`（2026-10-19 补充：增加 `shopName`）；
  - `POST /admin-api/kol/cleanup-items/{id}/retry`：只能重试 `FAILED` 的明细，权限 `kol:edit`。
- 模块边界：编排仍放在 `order` 模块，经由各模块的 `api` 调用。**不得**跨模块直接写 SQL（任务 25a、17b、28 都纠正过这个问题）。

### 3.7 注册（01.01、02.01）

- **商务邀请码改为可以手动输入**：
  - 现在的注册码是 32 位随机串，没法手输。改为 **8 位**，字符集为大写字母和数字，去掉容易混淆的 `0 O 1 I L`，全局唯一，输入时不区分大小写；
  - 注册链接改为 `{kolPortalBase}/register?code=XXXXXXXX`；
  - 已有的 32 位注册码作废：迁移时吊销，商务下次获取时自动生成新码。
- **防枚举**：`GET /kol-api/register/{code}`（预览）和 `POST /kol-api/register` 按来源 IP 限流，例如每分钟 20 次，复用现有的限流做法；超出返回 429。预览接口的响应为 `{bdUserName, tenantName}`：`bdUserName` 是商务的显示名，`tenantName` 沿用现有字段，不返回其他信息（2026-10-19 补充：明确字段名）。
- **注册请求 `POST /kol-api/register`**：
  - 必填：`code`（8 位）、`email`、`emailCode`、`password`、`name`（账户名）；
  - 选填：`phone`、`whatsapp`、`primaryPlatform`、`primaryHandle`、`country`、`primaryUrl`；
  - 任务 25a 中「WhatsApp 必填」、核心社媒必填的规则取消；
  - 只填了 `primaryPlatform` 或 `primaryHandle` 其中一项时，返回校验错误；
  - `crm_kol` 新增 `phone` 列，E.164 格式，可以为空。
- `GET /admin-api/kol/register-code` 的响应：`{code, url}`，`code` 为 8 位邀请码。
- 邮箱验证码、重复邮箱拦截、登录、找回密码（02.02、02.03）保持现状，并保证测试不退化。

### 3.8 其他

- 新增错误码都登记在 `shared.ErrorCode`，`messages*.properties` 中英文都要有。
- 设计说明书：§4.2 改写（三种状态、审核、推广配置版本、平台地址、清理删除微店商品）；§5.2 的表结构；§6.2 / §6.3 标记区间内的接口清单（`ApiCatalogConsistencyTest` 必须通过）。

## 4. 测试要求

| 场景 | 期望 |
|---|---|
| 迁移 | 四种原状态映射正确；`landing_url` 复制到每个平台的地址；旧注册码被吊销（集成测试） |
| 状态 | 三种状态的推导与组合；显示用关系状态的优先级（`INVALID` 高于 `PENDING_UPDATE`）；登录状态包括冻结、未激活、锁定三种情况 |
| 列表 | 新字段齐全；按审核状态、关系状态（含待更新）、登录状态筛选；商务本人范围不变 |
| 审核 | 只能审核一次；邮箱和编号不能改；保存后变为已通过；写入推广记录 |
| 推广配置 | 审核未通过时 409；必填项校验；每个平台必须有地址；版本号递增、生效时间正确；缺项时 422 的字段名；改邀请码或跳转平台时刷新链接索引 |
| 关系状态 | 改为推广中要求审核通过且配置齐全；无效可以改回待推广；记录写入 |
| 清理 | 自动和手动清理都生成明细；删除成功、商品已不存在视为成功、删除失败记原因；手动重试只能重试失败的明细；不做业务重试；数据范围与公司隔离 |
| 注册 | 8 位码大小写不敏感、可以手动输入；限流返回 429；选填字段可以为空；社媒平台与 ID 只填一项时被拒；02.02 和 02.03 的已有测试全部通过 |
| 架构 | `ArchitectureTest`（模块循环依赖、`api` 边界）、`ApiCatalogConsistencyTest` 通过 |

## 5. 检查与交付

- `mvn spotless:apply && mvn verify && mvn verify -Pdocker-it` 全部通过，覆盖率门槛不变。
- 执行报告 `docs/04-开发任务/29-达人注册审核状态与推广配置-后端-执行报告.md`：每条决定的落地情况、迁移说明、接口示例、测试清单、偏离与遗留。
- 收尾：只删除自己创建的 Docker 资源（Testcontainers 留下的残留容器等），不做全局清理；在报告中写明。
- 提交遵循 Conventional Commits，推送分支，按 PR 模板创建 PR。**不要合并 PR，不要推送 `main`。**

## 6. 禁止事项与停止条件

**禁止**：

- 改变第 2 节的决定，或擅自改动第 3 节的契约；
- 修改已合并的迁移脚本；新增依赖；删除或弱化测试；降低覆盖率门槛；
- 跨模块直接写 SQL；
- 推送 `main`、合并 PR；`push --force`、`reset --hard`、改写已推送历史。

**停下来报告**（推送已完成的提交，以 `[WIP]` 标题创建 PR，并在 PR 中说明）：

- 契约中某一项无法实现，或实现时需要改动契约；
- 微店删除接口在 09a 中的定义不足以完成删除；
- 迁移会破坏现有数据，且无法安全映射；
- 合理排查后验证仍然失败。

完成后回复一段总结：PR 链接、做了什么、测试结果、遗留问题。
