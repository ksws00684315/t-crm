# 任务 18（后端）：订单与退款 Excel 导入（兜底）

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 15b、16b、19a、19b、20 同时进行。本任务只做后端；导入按钮放在 19a 的订单页面上，按第 4.6 节的接口约定对接。
> - 19a、19b 也会改 `order` 模块（查询字段、达人端订单接口），本任务只新增导入相关代码，尽量不改它们会动的类。
> - 迁移脚本版本号固定为 `V202610172150`。

---

## 1. 背景

微店订单原本由任务 13 每 5 分钟通过接口同步。设计 §7.1 规定了兜底方式：接口不可用时，运营用 Excel 导入订单，模板字段与接口字段一一对应，导入的订单与接口同步走同一套标准化和归因（设计 §4.6「后续任务」）。

本任务实现两个导入：

- **订单导入**：一行一个订单明细；
- **退款导入**：一行一笔已完成的退款。

佣金不在本任务范围。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`。
2. 设计说明书：
   - §3.1 权限码 `order:manage`；
   - §4.6 订单同步与归因（含「后续任务」一段）；
   - §4.7 退款；
   - §5.2 `crm_order`、`crm_order_item`、`crm_refund`、`crm_task`；
   - §6.2 订单接口；
   - §7.1 微店；
   - §8.1 订单状态标准化。
3. `docs/04-开发任务/13-订单同步与归因-后端-执行报告.md`：标准化、稳定子订单键、归因、商务快照、退款金额换算。
4. 现有代码：
   - `task.api.ImportTaskApi`、`ImportRowProcessor`、`TaskType`；
   - 已有导入的写法：`product` 模块的 `ProductImportService` / `ProductImportProcessor` / `ProductImportColumn`；
   - `order` 模块的 `OrderWriter`、`OrderNormalizer`、`OrderAttribution`、`OrderStatus`；
   - `product.api.ShopAccessApi`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/18-order-import` 已从最新的 `main` 检出。
- **只修改 `backend/`、你的执行报告，以及第 4.7 节列出的文档。**
- 新增错误码或导入失败原因时，按规范 03 在 `admin-ui/src/locales/{zh-CN,en-US}.ts` 补文案，前端其他文件不改。
- JDK 21 下执行 `mvn spotless:apply`、`mvn verify`、`mvn verify -Pdocker-it`，全部通过。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 迁移 `V202610172150__order_import_task_types.sql`

`crm_task` 的类型 CHECK 约束加入 `ORDER_IMPORT`、`REFUND_IMPORT`。`TaskType` 新增这两个值，行数上限都是 20,000。

不改订单、退款表结构：`crm_order.source = 2` 表示导入，`import_batch_id` 写任务 ID（`crm_task.id`）。

### 4.2 订单导入模板（一行一个明细）

| 列 | 必填 | 规则 |
|---|---|---|
| 微店店铺 ID | ✓ | 本公司已登记的店铺（`crm_shop.weidian_shop_id`）；不存在返回该行失败 |
| 订单号 | ✓ | 数字，≤ 32 位 |
| 子订单号 | | 有就用；为空时按任务 13 的规则生成稳定键（当前店铺商品 ID + SKU ID + 该明细在本文件同一订单中的行序号） |
| 订单状态 | ✓ | 中文或英文标准状态名：待付款 / 已付款 / 已发货 / 已收货 / 已完成 / 已关闭（UNPAID / PAID / SHIPPED / RECEIVED / COMPLETED / CLOSED）。`raw_status` 写 `IMPORT:` 加标准状态英文名 |
| 下单时间 | ✓ | `yyyy-MM-dd HH:mm:ss`，按北京时间解析，存 UTC |
| 支付时间 | | 同上；未支付为空。状态为已付款及之后时必填 |
| 订单实付（元） | ✓ | ≥ 0，最多两位小数；同时写入 `total_amount` 与 `pay_amount` |
| 运费（元） | | 默认 0 |
| 商品 ID | ✓ | 当前店铺的微店商品 ID，数字 |
| SKU ID | | 无 SKU 为空 |
| 商品名称 | ✓ | ≤ 255 |
| 型号 | | ≤ 255 |
| 数量 | ✓ | 正整数 |
| 明细实付（元） | ✓ | ≥ 0，最多两位小数 |

处理规则：

1. **逐行处理**。同一订单的多行，每一行都按「订单头 + 本行明细」调用与接口同步相同的写入和归因逻辑（`OrderWriter` 的语义：订单按 `(shop_id, weidian_order_id)` 幂等，明细按 `(order_id, sub_order_id)` 幂等，每次写入后对整单重新执行归因）。最后一行处理完后，订单状态和归属就是正确的。不要求同一订单的行连续。
2. **与接口同步的关系**：
   - 订单已存在且 `source = 1`（接口同步）时，该行失败，原因为「该订单已由接口同步，不能导入覆盖」；
   - 订单已存在且 `source = 2` 时，更新；
   - 导入的订单之后被接口同步到时，由接口数据覆盖，`source` 改为 1。这是任务 13 现有行为，确认即可，不需要改。
3. **同一订单在文件中的订单头不一致**（状态、时间、实付、运费不同）：以后出现的行为准，在报告中说明。
4. **归因与商务快照**：与任务 13 完全相同，包括按支付时刻匹配对应关系、一单一达人、冲突和未匹配进入人工匹配池、所属商务快照。
5. **数据最小化**：模板没有买家信息列，`buyer_hash` 为空。

### 4.3 退款导入模板（一行一笔已完成的退款）

| 列 | 必填 | 规则 |
|---|---|---|
| 微店店铺 ID | ✓ | 同上 |
| 退款单号 | ✓ | ≤ 32 位 |
| 订单号 | ✓ | 本店铺已存在的订单（导入或同步而来均可）；不存在时该行失败 |
| 子订单号 | | 能唯一对应到明细时写 `order_item_id`，否则为空，与任务 13 一致 |
| 退款金额（元） | ✓ | > 0，最多两位小数 |
| 发起时间 | ✓ | 北京时间 |
| 完成时间 | ✓ | 北京时间，不早于发起时间 |

处理规则：

1. 只导入**已完成**的退款：`raw_status` 写 `10`，与任务 13 中「完成」的取值一致，这样明细的 `refund_amount` 与订单的退款合计会自动计入。
2. 退款单号已存在时，该行失败，原因为「退款单已存在」。不覆盖接口同步来的退款。
3. 写入后按任务 13 的方法重算该订单各明细的 `refund_amount`。

### 4.4 接口

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /admin-api/order/order-imports/template` | `order:manage` | 下载订单导入模板（按请求语言生成表头） |
| `POST /admin-api/order/order-imports` | `order:manage` | multipart `file`，返回 202 + `{taskId}` |
| `GET /admin-api/order/refund-imports/template` | `order:manage` | 下载退款导入模板 |
| `POST /admin-api/order/refund-imports` | `order:manage` | multipart `file`，返回 202 + `{taskId}` |

- 任务进度和错误文件沿用现有的 `/admin-api/task/tasks/{id}`。
- 两个导入接口都加 `@AuditLog`。

### 4.5 不在本任务范围

- 管理端导入按钮与对话框（19a 按第 4.6 节接入）。
- 佣金。
- 识别微店后台导出文件的原始格式。如果以后客户提供微店后台导出的样例，再另加映射。

### 4.6 接口约定（与 19a 共用，不得改动）

```text
GET  /admin-api/order/order-imports/template   → xlsx 附件
POST /admin-api/order/order-imports            → 202 data: { taskId }
GET  /admin-api/order/refund-imports/template  → xlsx 附件
POST /admin-api/order/refund-imports           → 202 data: { taskId }
```

### 4.7 文档

- 设计说明书：
  - §4.6：「后续任务」中订单导入一条改为已实现，写明两个模板与第 4.2、4.3 节的规则；
  - §5.2：`crm_task` 的类型；
  - §6.2：用上面四个接口替换 `POST /admin-api/order/import` 一行。

## 5. 执行步骤

1. 迁移与 `TaskType`。
2. 订单导入：列定义、行处理（复用订单写入与归因）、接口。
3. 退款导入。
4. 测试、文档、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 模板 | 中英文表头；表头不符合时拒绝 |
| 订单导入 | 多行同一订单得到一个订单和多条明细；子订单号为空时生成稳定键（重复导入同一文件不新增明细）；状态与时间解析；必填与格式校验返回对应的失败原因 |
| 归因 | 单达人 `ATTRIBUTED`；多达人 `CONFLICT`；有未匹配明细 `UNMATCHED`；未支付 `PENDING`；所属商务快照按支付时刻 |
| 与同步的关系 | `source = 1` 的订单不被导入覆盖；`source = 2` 的订单被导入更新；`import_batch_id` 为任务 ID |
| 退款导入 | 订单不存在时失败；退款单号重复时失败；`refund_amount` 与订单退款合计正确；只写 `raw_status = 10` |
| 权限与隔离 | 无 `order:manage` 返回 403；其他公司的店铺 ID 视为不存在 |

集成测试至少覆盖：一个文件导入多订单、再导入同一文件的幂等；导入后的订单出现在 `GET /admin-api/order/orders` 和人工匹配池中。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/18-订单与退款导入-后端-执行报告.md`：迁移、两个模板的列与规则、接口与示例、与接口同步的关系、测试清单、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改已合并的迁移脚本；
- 新增依赖；
- 计算或写入佣金；
- 修改 `admin-ui/`（语言包除外）、`kol-portal/`、`redirect/`、`deploy/`；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 现有导入框架不支持本任务需要的处理方式，需要修改 `task` 模块的公共接口；
- 设计说明书与本提示词冲突；
- 验证在合理排查后仍失败。
