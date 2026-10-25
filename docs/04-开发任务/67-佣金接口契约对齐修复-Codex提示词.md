# 任务 67：佣金接口与契约对齐（修复任务 62 的响应字段偏差）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/67-commission-contract-align`，分支 `task/67-commission-contract-align`，从最新 `main` 检出（已包含任务 62 #124 和任务 64 #123）。后端端口 `TCRM_ADMIN_PORT=18671`。**不新增迁移**；如果确实需要，先停下来报告。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**、规范 01～05，以及 ADR-032 附录 A（#112 字段对照）、附录 B（#116 字段对照）和设计说明书 §6.2、§6.3。**契约以 ADR-032 和设计说明书为准**。

## 1. 背景

任务 62（#124）已合并。架构审查对照附录 A 后，发现管理端几个接口的响应与契约不一致。产品方的管理端 PR #112 是按契约写的，所以需要后端改为与契约一致，前端不改。

## 2. 已确认的偏差（必须修改）

1. `GET /admin-api/commission/incomes` 的响应目前是 `{page:{items,total,page,size}, summary}`。契约规定为分页字段与 `summary` 平铺：`{items,total,page,size,summary}`。
2. 收入列表的 `summary` 缺少 `commissionPaid`、`prepayPaid`。契约中 `summary` 的字段是 `{paid,commissionPaid,prepayPaid,creators,orders,quantity,unverified}`。
3. `GET /admin-api/commission/incomes/{id}` 的 `lines[]`，以及 `GET /admin-api/commission/settlement-details` 的 `lines[]`，缺少 `product`（商品名称）和 `sku`（SKU 名称）。数据来自订单商品行；跨模块只能经 `order.api` 取得，必要时扩展 `OrderSettlementApi` 的只读投影。

## 3. 逐字段复核（必须做）

除了上面 3 条，请把任务 62 提供的全部接口，逐个字段对照附录 A、附录 B 和设计说明书 §6.2、§6.3：
- 管理端：`incomes`、`incomes/{id}`、`settlement-configs/current`（GET/PUT）、`settlement-details`；
- 达人端：`commissions/overview`、`fund-records`、`orders` 中的佣金字段、`analytics/overview` 中的佣金数字。

核对内容包括：字段名、层级、可空性，以及类型（ID 与金额为字符串，分页 `total` 为数字，`unverified` 为数字）。核对结果写成表格放进执行报告，每行一个字段，注明「一致」或「已修正」。

如果发现契约本身需要调整（例如前端已经按另一种写法实现），**先停下来报告**，不要自行修改契约。

## 4. 测试

- 为每个管理端接口补充控制器切片测试，用 JSON 路径断言契约中规定的每个字段都存在、层级正确（例如 `$.data.items`、`$.data.summary.commissionPaid`、`$.data.lines[0].product`）。目的是防止以后再出现字段偏差；
- `product`、`sku` 的取数补单元测试，包括订单商品行没有 SKU 的情况；
- 集成测试中已有的收入列表、结算明细断言随响应结构同步修改，不得删除或弱化。

## 5. 与任务 63 的关系

任务 63（#121）也会修改 commission 模块（资金来源、提现占用）。本任务只改上面列出的接口的响应结构和取数，不改提现相关的代码。两边都改到同一个文件时，后合并的一方负责解决冲突。

## 6. 检查与交付

- 按 CI 的步骤执行，不加测试过滤：`mvn -B clean`、`mvn -B spotless:check`、**`mvn -B verify`（只跑单元测试，覆盖率门槛必须单独通过）**、`mvn -B verify -Pdocker-it`。执行报告中分别贴出两次 `verify` 的结果。检查交给子代理执行。
- 没有界面改动，不需要浏览器验收；改动了错误码时，同步 admin-ui 的中英文文案并跑 admin-ui 的检查。
- 不连接共享库写数据；测试数据加 `t67_` 前缀。不得停止或删除共享中间件，不得重建负责人的预览。
- 执行报告含逐条复核表，提交 PR。不要合并、不要推 main、不要改任务索引。
