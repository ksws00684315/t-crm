# 任务 69：提现接口与契约对齐（任务 63 的接口逐字段复核）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/69-withdrawal-contract-align`，分支 `task/69-withdrawal-contract-align`，从最新 `main` 检出（已包含任务 62、63、67）。后端端口 `TCRM_ADMIN_PORT=18691`。**不新增迁移**；如果确实需要，先停下来报告。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**、规范 01～05，以及 ADR-032 附录 A（#112 字段对照）、附录 B（#116 字段对照）和设计说明书 §6.2、§6.3。**契约以 ADR-032 和设计说明书为准**。可以参考任务 67 的做法和执行报告（[67 执行报告](67-佣金接口契约对齐-执行报告.md)）。

## 1. 背景

任务 67 只复核了任务 62 的接口。用 #112 的管理端接真实后端联调时，发现任务 63 的 `finance-tasks` 也与契约不一致。产品方的前端（#112 管理端、#116 达人端）是按契约写的，所以需要后端改为与契约一致。

## 2. 已确认的偏差（必须修改）

1. `GET /admin-api/commission/finance-tasks` 每行返回的是 `taskNo`、`taskType`、`amountCny`。设计说明书 §6.2 和附录 A 规定的是 `applicationNo`、`type`、`amount`（另有 `id`、`kolId`/`kolCode`/`kolName`、`currency`、`status`、`createdAt`）。请改为契约字段名。`bdId`、`bdName` 可以保留，在对照表中注明是额外字段。
2. ADR-032 附录 A 中 `SettlementQuery` 的 `shop` 与设计说明书 §6.2、后端实现的 `shopId` 不一致。以 `shopId` 为准，修改附录 A 的文字。只改文档，不改接口。

## 3. 逐字段复核（必须做）

把任务 63 提供的全部接口，逐个字段对照附录 A、附录 B 和设计说明书 §6.2、§6.3：
- 管理端：`finance-tasks`、`withdrawals`（列表）、`withdrawals/{id}`（详情，含 `allocations[]`、`history[]`、`payment`、`account`）、`withdrawals/{id}/orders`、`withdrawals/{id}/recipient`、审核、驳回、付款登记的请求体、`withdrawals/export`、`withdrawal-occupations`、`withdrawal-freezes`；
- 达人端：`withdrawals/conditions`、`withdrawals/recipient-options`、`POST /kol-api/withdrawals` 的请求体、我的提现列表与详情、撤回、驳回后重新提交。

核对内容包括：字段名、层级、可空性，以及类型（ID 与金额为字符串，分页 `total` 和计数为 JSON 数字，`total` 不要被全局 long 序列化成字符串）。核对结果写成表格放进执行报告，每行一个字段，注明「一致」或「已修正」。

如果发现契约本身需要调整（例如前端已经按另一种写法实现，或者契约内部前后矛盾），**先停下来报告**，不要自行修改契约。第 2 节第 2 条除外，那一条已经确认。

## 4. 测试

- 为每个管理端和达人端提现接口补充控制器切片测试，用 JSON 路径断言契约中的每个字段都存在、层级正确，例如 `$.data.items[0].applicationNo`、`$.data.items[0].type`、`$.data.items[0].amount`、`$.data.total` 为数字；
- 集成测试中已有的断言随字段名同步修改，不得删除或弱化。

## 5. 检查与交付

- 按 CI 的步骤执行，不加测试过滤：`mvn -B clean`、`mvn -B spotless:check`、**`mvn -B verify`（只跑单元测试，覆盖率门槛必须单独通过）**、`mvn -B verify -Pdocker-it`。执行报告中分别贴出两次 `verify` 的结果。检查交给子代理执行。
- 改动了错误码时，同步 admin-ui 和 kol-portal 的中英文文案并跑对应前端检查。
- 不连接共享库写数据；测试数据加 `t69_` 前缀。不得停止或删除共享中间件，不得重建负责人的预览。
- 与任务 68 可能改到同一个文件，后合并的一方负责解决冲突。
- 执行报告含逐条复核表，提交 PR。不要合并、不要推 main、不要改任务索引。
