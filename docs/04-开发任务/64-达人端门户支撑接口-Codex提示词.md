# 任务 64：达人端门户支撑接口（个人资料修改、订单与商品查询扩展）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/64-kol-portal-apis`，分支 `task/64-kol-portal-apis`。**开工前先 `git merge origin/main`，并确认任务 61（契约）已合并**。后端端口 `TCRM_ADMIN_PORT=18641`。原则上不新增迁移；确实需要时，版本号只用 `V202610251200`～`V202610251209`，并先报告。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**、规范 01～06，以及设计说明书 §6.3（达人端接口）。

## 1. 背景

产品方的 PR #116 把达人端重排为 4 个入口：工作台、订单明细、资金明细、个人资料。其中的后端部分改由本侧实现。资金相关的接口由任务 62、63 负责；本任务负责与佣金无关的门户支撑接口。

## 2. 范围

- 读取 #116 分支（`origin/task/60-kol-portal`）中以下文件，找出与佣金无关的后端改动和达人端的调用：
  - `backend/.../kol/controller/KolAuthController.java`（`PUT /kol-api/me`）、`KolMeUpdateRequest`、`KolPortalProfileService`、`KolPortalProfileDTO`；
  - `order/controller/KolOrderController.java`、`KolOrderService`、`KolOrderFilterDTO`（订单筛选扩展）；
  - `product/controller/KolPortalItemController.java`；
  - `kol-portal/src/api/creator.ts`、`kol-portal/src/api/auth.ts`。
- 按任务 61 的契约实现：
  - **达人修改本人资料**：只允许修改契约规定的字段；邮箱和达人编号只读；联系方式与社媒字段的校验沿用注册规则；写审计；达人 ID 从登录态取得；
  - **达人端订单查询扩展**：筛选与字段；只返回本人订单；退款状态与类型沿用任务 40 的口径；
  - **达人端商品查询扩展**：只返回本人的对应关系。
- **第一步：契约增补**。任务 61（ADR-032 §14）把达人端门户支撑接口留给本任务：`GET /kol-api/analytics/overview`（工作台概览）、`PUT /kol-api/me`（修改本人资料）、达人端商品查询扩展。请先对照 #116 的实现与 `creator.ts`、`auth.ts`，把这几个接口的路径、权限、参数、响应字段、错误码补进设计说明书 §6.3，作为本 PR 的第一个提交，然后再实现。工作台概览中与佣金相关的数字，只能调用 commission 模块 `api` 中任务 62 提供的方法；任务 62 未合并时，先返回契约约定的占位值，并在报告中说明。
- `/kol-api/orders` 的扩展分工：订单号、商品名等筛选与非佣金字段由本任务实现；`settlementStatus`、`rateSnapshot`、`commissionAmount` 等佣金字段由任务 62 通过 commission `api` 提供，本任务不实现佣金计算。
- 增补之外，契约没写、而 #116 前端需要的字段，先停下来报告，不要自行扩展。
- 规范要求同任务 62：Mapper 与公司插件、`api` 包边界、错误码登记、ID 为字符串、接口权限声明。

## 3. 测试

- 资料修改：允许的字段、禁止的字段、校验失败、审计；
- 订单与商品查询只返回本人数据，筛选参数正确；
- 公司隔离。

## 4. 检查与交付

- `mvn -B clean`、`spotless:check`、`verify -Pdocker-it`，不加测试过滤。检查交给子代理执行。
- 联调连接共享中间件，测试数据加 `t64_` 前缀，不修改已有达人的资料。不得停止或删除共享中间件，不得重建负责人的预览。
- 执行报告含逐条复核表，提交 PR。不要合并、不要推 main、不要改任务索引。GitHub Actions 已暂停，不要等待 `gh pr checks`。
