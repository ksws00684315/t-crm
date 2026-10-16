# 任务 19a（管理端，含少量后端）：订单列表、人工匹配池、手动同步与导入入口

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 15b、16b、18、19b、20 同时进行。
> - 导入按钮按任务 18 的接口约定（第 4.5 节）开发，不要求 18 已合并。
> - 19b 也会在 `order` 模块新增达人端接口，本任务只改管理端订单查询相关的类。
> - 本任务没有迁移脚本。

---

## 1. 背景

任务 13 已在后端实现微店订单同步、归因、人工匹配和查询，但管理端还没有界面。运营需要：

- 查看订单及归属；
- 处理不能自动归因的订单（人工匹配池）；
- 店铺同步出问题时手动触发同步、查看同步状态；
- 在接口不可用时导入订单和退款（任务 18）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02 第 7 节、03、05、06 第 3 节）。
2. 设计说明书：
   - §3.1 权限码 `order:view`、`order:manage`、`kol:view`；
   - §3.2 第 2 条：订单按所属商务快照过滤；
   - §4.6 订单同步与归因；
   - §4.7 退款；
   - §6.2 订单接口；
   - §8.1 订单状态标准化；
   - §8.5 人工匹配池。
3. `docs/04-开发任务/13-订单同步与归因-后端-执行报告.md`：接口字段与错误码。
4. `docs/04-开发任务/06e-管理端布局与交互体验-执行报告.md` 第 2 节：新页面必须使用 `ListSearch`、`DataTable`、`CopyText`。
5. 现有代码：
   - 后端 `order` 模块的 `OrderController`、`OrderService`、`OrderDTO`；
   - `kol.api.KolApi.listByIds`、`system.api.BdUserApi.listByIds`、`product.api.ShopAccessApi`；
   - 管理端 `components/ImportDialog.vue` 与原商品页的用法；
   - `router/routes.ts`、`utils/permission.ts`、`locales/`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/19a-order-admin-ui` 已从最新的 `main` 检出。
- **只修改 `admin-ui/`、`backend/` 中第 4.1 节列出的范围、你的执行报告，以及第 4.6 节列出的文档。**
- 后端：JDK 21 下 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it` 全部通过。
- 管理端：`pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check` 全部通过。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 后端补充（只限以下两项）

1. **订单列表补显示字段**：`GET /admin-api/order/orders` 和 `GET /admin-api/order/unmatched` 的每行增加：
   - `shopName`；
   - `kolCode`、`kolName`（订单有达人时）；
   - `bdUserName`（所属商务快照的显示名，昵称优先，没有昵称用用户名；为空表示归公司）。

   按页批量查询：通过 `kol.api`、`system.api`、`product.api` 读取，不逐行查。明细的 `kolId` 同样补 `kolCode`。
2. **店铺同步状态**：新增 `GET /admin-api/order/sync-states`，权限 `order:view`。
   - 返回本公司全部店铺：`[{shopId, shopName, enabled, authorized, lastSuccessAt, lastError}]`，没有同步记录的店铺 `lastSuccessAt` 为空；
   - 订单页面的店铺筛选下拉也用这个接口，所以只有 `order:view`、没有 `product:view` 的账号也能选店铺。

两项都写单元测试，并补集成测试。不改归因、同步和人工匹配的逻辑。

### 4.2 菜单与路由

新增菜单分组「订单」，路由 `/order`：

| 页面 | 路由 | 权限 |
|---|---|---|
| 订单列表 | `/order/orders` | `order:view` |
| 人工匹配池 | `/order/unmatched` | `order:manage` |

### 4.3 订单列表

1. **筛选**：店铺、支付时间区间（按浏览器本地时区选择，转成 UTC 传给后端；`payTo` 不含）、达人编号、订单状态、归因状态。
2. **列**：
   - 订单号（`CopyText`）、店铺、支付时间、订单状态、实付、退款合计；
   - 达人（编号 + 姓名，可点击打开达人详情抽屉，需要 `kol:view`）；
   - 所属商务（空时显示「公司」）；
   - 归因状态（标签：已归属 / 非达人 / 冲突 / 未匹配 / 待支付）。
3. **展开行**显示明细：商品 ID、商品名称、型号、数量、明细实付、退款、归属达人编号、归因方式（商品 ID / 人工 / 非达人 / 冲突 / 未匹配）、人工原因。
4. **工具栏**（有 `order:manage` 时）：
   - 「同步状态」：打开对话框，列出各店铺的启用与授权状态、最近成功时间、最近错误；每个启用且已授权的店铺有「立即同步」按钮，调用 `POST /admin-api/order/syncs`，返回 202 时提示「已开始同步」，409 `ORDER_SYNC_RUNNING` 时提示对应文案；
   - 「导入订单」「导入退款」：使用 `ImportDialog`，按第 4.5 节的接口，模板文件名「订单导入模板.xlsx」「退款导入模板.xlsx」，完成后刷新列表。
5. 数据范围为「本人」的账号，后端已按所属商务快照过滤，前端不另做处理。

### 4.4 人工匹配池

1. 列表：`GET /admin-api/order/unmatched`，筛选店铺与支付时间区间，列与订单列表相同，默认展开明细。
2. **每行的操作**（`order:manage`）：
   - **指定达人**：对话框中搜索达人（`GET /admin-api/kol/kols` 按关键字，需要 `kol:view`；没有该权限时不显示此操作），填写原因（必填，≤ 255），调用 `PUT /admin-api/order/orders/{id}/attribution` `{kolId, reason}`；
   - **标为非达人订单**：填写原因，调用同一接口 `{nonKol: true, reason}`；
   - **重新匹配**：确认后调用 `POST /admin-api/order/orders/{id}/reattribution`，用于补录对应关系之后。
3. 操作成功后刷新列表；处理完成的订单会自然离开匹配池。
4. 未支付订单的人工指定返回 409，显示通用冲突文案即可。

### 4.5 接口约定（与 18 共用，不得改动）

```text
GET  /admin-api/order/order-imports/template   → xlsx 附件
POST /admin-api/order/order-imports            → 202 data: { taskId }
GET  /admin-api/order/refund-imports/template  → xlsx 附件
POST /admin-api/order/refund-imports           → 202 data: { taskId }
```

### 4.6 文档

- 设计说明书 §6.2 订单接口：列表增加的显示字段，以及 `GET /admin-api/order/sync-states`。

### 4.7 不在本任务范围

- 达人端订单页面（19b）。
- 佣金列（以后的佣金任务）。
- 订单导出。

## 5. 执行步骤

1. 后端第 4.1 节两项及测试。
2. 类型与接口封装。
3. 订单列表（筛选、展开明细、同步状态、导入入口）。
4. 人工匹配池。
5. 菜单、权限、语言包、测试、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 后端补字段 | 列表与匹配池返回店铺名、达人编号与姓名、商务显示名；归公司时商务为空；每页查询次数固定，不随行数增加 |
| 同步状态接口 | 无同步记录的店铺也出现；跨公司隔离；无 `order:view` 返回 403 |
| 菜单与权限 | 只有 `order:view` 看不到人工匹配池、同步和导入按钮；没有 `kol:view` 时匹配池不显示「指定达人」 |
| 订单列表 | 筛选参数正确（时区换算、`payTo` 不含）；归因状态标签；展开明细；同步 202 与 409 的提示 |
| 人工匹配 | 三种操作的请求体；原因必填；成功后刷新 |
| 国际化 | 新键在两份语言包中一致；残留中文测试通过 |

**浏览器验证**（写进报告并附截图）：

- 用本地后端在库中准备几笔订单（可直接写库，或在 18 合并后用导入），覆盖已归属、冲突、未匹配三种；
- 超管看到全部订单，商务只看到快照为本人的订单；
- 在匹配池完成一次指定达人和一次标为非达人；
- 18 未合并时导入入口用模拟数据截图，并在报告中说明。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/19a-订单页面-管理端-执行报告.md`：后端补充、页面与权限对照、测试清单、浏览器验证与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改第 4.1 节以外的后端代码；
- 新增依赖；
- 改变第 4.5 节的接口约定；
- 修改 `kol-portal/`、`redirect/`、`deploy/`；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 接口的实际字段与任务 13 的执行报告不一致；
- 验证在合理排查后仍失败。
