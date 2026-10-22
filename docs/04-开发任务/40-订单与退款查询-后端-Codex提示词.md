# 任务 40 后端：订单详情、退款状态与查询、订单链路、同步监控（1018 表第 39、42、43、44 行）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/40-order-backend`，分支 `task/40-order-backend`，已从最新 `main` 检出。后端端口 `TCRM_ADMIN_PORT=18401`。迁移版本号只用 `V202610221300`～`V202610221319`。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，其中的工作目录、共享中间件、子代理检查、交付规则全部适用。
>
> 配套前端是任务 40 前端（另一个代理并行执行，会合并本分支联调）。**第 4 节的接口契约是两边的约定**，需要改动时先写进执行报告「契约变更」并通知架构负责人。

## 1. 需求原文与范围

进度表「功能评估-P0」，实现前逐字阅读：

| 行 | 编号 | 本任务做的部分 |
|---|---|---|
| 39 | 08.02 | 订单状态、退款状态、退款类型**分别记录和展示**：退款状态「无退款、退款中、退款成功」，退款类型「仅退款、退货退款」；销售按支付时间、成功退款按退款成功时间统计 |
| 42 | 09.03 | Excel / CSV 导入兜底：**保留真实数据截至时间** |
| 43 | 09.04 | 订单查询（按订单号、渠道、店铺筛选；列表字段；复制订单号、详情、进入链路）、订单详情、退款查询、退款详情、订单链路（串联商品创建任务、达人商品链接、订单、退款）、任务监控（来源、最后成功同步时间、缺字段、失败任务） |
| 44 | 10.01 | 人工匹配池：查看依据（来源 / 目标商品、达人映射等证据） |

**不做（佣金结算部分）**：09.04 中的「账务流水」「提现占用」以及各金额与 18～20 项的衔接——链路接口对这两部分返回空，前端显示「开发中」；10.02 已付佣金差额。真实微店的状态取值待 M0 核实（09.01、09.02），本任务按设计说明书映射并用模拟数据测试。

## 2. 现状

- `crm_order`：`weidian_order_id`、`shop_id`、`kol_id`、`bd_user_id`、`order_status`（`UNPAID / PAID / SHIPPED / RECEIVED / COMPLETED / CLOSED / UNKNOWN`）、`raw_status`、`attribution_status`、`order_time`、`pay_time`、`total_amount`、`pay_amount`、`freight`、`discount`、`source`、`import_batch_id`、`last_sync_time`。
- `crm_order_item`：`sub_order_id`、`weidian_item_id`、`sku_id`、`item_name`、`sku_name`、`quantity`、`pay_amount`、`refund_amount`、`kol_id`、`product_id`、`kol_item_id`、归属字段。
- `crm_refund`：`order_id`、`order_item_id`、`weidian_refund_no`、`amount`、`raw_status`、`refund_time`、`finish_time`。**没有退款类型和标准化的退款状态**。
- `crm_order_sync_state`：每店铺 `order_cursor`、`refund_cursor`、`last_success_at`、`last_error`。导入任务在 `crm_task`。
- 接口：`GET /admin-api/order/orders`（列表）、`/syncs`、`/sync-states`、`/unmatched`、人工指定与改归属。管理端「退款查询」是「开发中」占位。
- 链接编码 `link_code` 由任务 39 新增、店铺编码 `shop_code` 由任务 37 后端新增（都在并行开发）。本任务先按 ID 实现，相关任务合并后合并 `main`，补上 `linkCode`、`shopCode` 字段，再转为 Ready。

## 3. 要做的事

### 3.1 退款类型与状态（08.02）

- `crm_refund` 新增 `refund_type`（`REFUND_ONLY` 仅退款 / `RETURN_REFUND` 退货退款 / `UNKNOWN`）、`refund_status`（`PROCESSING` 退款中 / `SUCCESS` 退款成功 / `CLOSED` 已关闭 / `UNKNOWN`）、`apply_time`（申请时间）。同步与导入时由 `raw_status` 与微店字段映射；映射表写在一个地方并有单元测试；无法识别的记 `UNKNOWN` 并保留原值。已有数据按现有字段回填。
- 订单级退款状态（列表与详情展示用，不落库也可）：没有退款记录为「无退款」；有任一 `PROCESSING` 为「退款中」；否则有 `SUCCESS` 为「退款成功」，并区分部分 / 全额（成功退款金额之和是否等于实付金额）。
- 只有 `SUCCESS` 计入成功退款金额；成功退款按退款成功时间（`finish_time`）统计。

### 3.2 订单查询与详情（09.04）

- 列表增加筛选：`orderNo`（微店订单号，精确）、`channel`、`shopId`；每项补充：`channel`、`shopName`、`shopCode`、`kolName`、`kolCode`、`orderStatus`、`refundStatus`（订单级，3.1）、`payAmount`、`freight`、`payTime`、`createTime`。
- 详情 `GET /admin-api/order/orders/{id}`：`merchantNo`（店铺的微店店铺标识）、`itemAmount`（商品金额，商品行金额之和）、`freight`、`totalAmount`、`payAmount`、`orderTime`、`payTime`、来源（接口同步 / 导入）、最后同步时间；商品行：`imageUrl`、`itemName`、`weidianItemId`、`skuName`、`kolName`、`kolCode`、`quantity`、`unitPrice`、`payAmount`、`refundAmount`、`status`。
- 商品主图：按商品行的达人商品 → 原商品取 `image_url`，取不到为 null。

### 3.3 退款查询与详情（09.04）

- `GET /admin-api/order/refunds`（`order:view`）：筛选 `refundNo`、`orderNo`、`channel`、`refundStatus`、`refundType`；分页；每项 `id`、`refundNo`、`orderId`、`orderNo`、`channel`、`shopName`、`shopCode`、`kolName`、`kolCode`、`refundType`、`refundStatus`、`amount`、`applyTime`、`finishTime`。
- `GET /admin-api/order/refunds/{id}`：基础信息 + `merchantNo`，逐行列出退款商品：`imageUrl`、`itemName`、`weidianItemId`、`skuName`、`kolName`、`kolCode`、`quantity`、`refundAmount`。退款行与订单商品行的对应以 `order_item_id` 为准；微店一笔退款涉及多个商品行时的结构待 M0 核实，按现有表结构实现并在报告中列出。

### 3.4 订单链路（09.04）

- `GET /admin-api/order/orders/{id}/chain`：按商品行串联
  - 商品创建任务：生成该达人商品的复制任务（`crm_copy_job` 的任务编号、创建时间）——通过达人商品的新商品 ID 与复制任务明细的 `target_item_id` 关联，找不到为 null；
  - 达人商品链接：`kolItemId`、`linkCode`、来源商品 ID、目标商品 ID、达人；
  - 订单商品行：数量、实付金额；
  - 退款：该商品行的退款记录（单号、类型、状态、金额、成功时间）；
  - `ledger`（账务流水）、`withdrawals`（提现占用）：返回 null，表示「开发中」。
- 跨模块数据只经对方的 `api` 包取得。

### 3.5 同步监控与导入截止时间（09.03、09.04）

- `GET /admin-api/order/sync-states` 扩展：每个店铺、分订单和退款两类返回：来源（接口 / 导入）、最后成功同步时间、最近错误、最近 24 小时失败次数、缺字段记录数（订单缺支付时间、商品行缺商品 ID 或金额等，字段清单写进设计说明书）。
- 失败任务：`GET /admin-api/order/sync-failures`，列出最近的同步失败与导入失败（店铺、类型、时间、错误、关联导入任务）。失败记录的存储由你决定（新表或复用 `crm_task`），写进设计说明书。
- 导入截止时间：订单导入与退款导入新增必填参数 `dataCutoffTime`（导入文件中数据的截至时间），随导入任务保存；同步监控中显示每个店铺最近一次导入的截止时间。

### 3.6 人工匹配池证据（10.01）

- `GET /admin-api/order/unmatched/{orderItemId}/evidence`：该商品行的微店商品 ID、所在店铺；同一商品 ID 在本公司的达人商品对应关系（达人、链接编码、生效起止，是否覆盖支付时间）；对应的原商品；未匹配原因（未匹配 / 缺规则 / 证据冲突，对应现有 `attribution_status`）；现有的人工处理记录。只读，权限同人工匹配池。

## 4. 接口契约（与前端约定）

字段名、取值以第 3 节为准；ID 与金额为字符串，时间为 ISO-8601 UTC。汇总：

| 接口 | 用途 |
|---|---|
| `GET /admin-api/order/orders`（扩展） | 订单列表，新增筛选 `orderNo`、`channel`、`shopId` 与 3.2 字段 |
| `GET /admin-api/order/orders/{id}` | 订单详情（3.2） |
| `GET /admin-api/order/orders/{id}/chain` | 订单链路（3.4） |
| `GET /admin-api/order/refunds`、`/{id}` | 退款查询与详情（3.3） |
| `GET /admin-api/order/sync-states`（扩展）、`GET /admin-api/order/sync-failures` | 同步监控（3.5） |
| 订单 / 退款导入（扩展） | 新增必填 `dataCutoffTime` |
| `GET /admin-api/order/unmatched/{orderItemId}/evidence` | 匹配证据（3.6） |

权限：查询类 `order:view`，商务（`OWN`）只看自己负责的达人的订单和退款；未归属达人的订单对商务不可见。导入、同步沿用现有权限。

## 5. 测试重点

- 退款类型与状态映射（每个已知取值与未知值）、订单级退款状态（无 / 退款中 / 部分成功 / 全额成功）、只计成功退款；
- 订单与退款列表的筛选、分页、商务数据范围、公司隔离；
- 详情与链路的数据关联（无复制任务、无退款、多笔退款）；
- 导入截止时间必填与保存；同步监控的缺字段与失败统计；
- 匹配证据（无对应关系、对应关系不覆盖支付时间、多个达人冲突）。

## 6. 不做

管理端页面（任务 40 前端）；账务流水、提现占用、佣金相关；微店真实状态取值核实（M0）。

## 补充（负责人 2026-10-22）：同步失败记录的清理

同步失败记录目前人工清理；「保留 N 天、每天自动清理」先预留，默认不打开。具体要求见 #97 的审查补充意见：人工清理接口 `DELETE /admin-api/order/sync-failures?olderThanDays=`（`order:manage`，1～3650 天，写审计）；系统配置 `order.sync-failure.retention-days`（默认 0 = 不自动清理，0～3650）与每日清理任务（参照 `OperLogRetentionJob`）。
