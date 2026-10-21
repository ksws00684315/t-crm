# 任务 37 后端：店铺管理、自动复制池与令牌容量（1018 表第 18～27 行）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/37-shop-backend`，分支 `task/37-shop-backend`，已从最新 `main` 检出。后端端口 `TCRM_ADMIN_PORT=18371`。迁移版本号只用 `V202610221000`～`V202610221019`。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，其中的工作目录、共享中间件、子代理检查、交付规则全部适用。
>
> 配套前端是任务 37 前端（另一个代理并行执行，会合并本分支联调）。**第 4 节的接口契约是两边的约定**，实现中需要改动的，先在执行报告「契约变更」一节写明，并通知架构负责人。

## 1. 需求原文

进度表 `CRM系统功能评估与进度跟踪表-1018.xlsx`「功能评估-P0」第 18～27 行（06.01～06.10）。实现前逐字阅读，复核时逐条对照。要点：

| 行 | 编号 | 功能说明要点 |
|---|---|---|
| 18 | 06.01 | 按店铺编码、店铺名称、渠道查询，重置、刷新、分页、店铺编码复制；展示店铺编码、名称、渠道、源店铺标记、自动复制池、微店店铺标识、授权状态及过期时间、商品 / 订单 / 退款接口状态、商品容量、启停状态 |
| 19 | 06.02 | 新建店铺必填：店铺名称、店铺渠道（下拉，目前仅微店）、最大商品数量（默认 20000）；列表展示当前商品数 / 最大商品数及容量刷新入口 |
| 20 | 06.03 | 每个店铺「去授权」入口，展示授权状态、过期时间；分别展示商品、订单、退款接口是否正常 |
| 21 | 06.04 | 区分源店铺与承接复制的店铺；自动复制池显示「已加入」「已退出」「不参与（源店铺）」；池内可用容量供令牌选择目标渠道时展示，未指定目标店铺时从对应渠道的自动复制池分配 |
| 22 | 06.05 | 启停状态与「更多」操作；店铺维护与源店标记、复制池、授权状态联动，避免把不可用店铺误作目标 |
| 23 | 06.06 | 店铺行「拉订单」「拉退款」，按所选店铺触发，结果进入现有订单、退款同步流程 |
| 24 | 06.07 | 生成令牌时选择达人（按名称、编码、邮箱搜索）、目标渠道（显示自动池剩余容量），填写商品数量和有效分钟；默认有效分钟 1440、商品数量 1 |
| 25 | 06.08 | 生成弹窗可选目标店铺；不指定时从目标渠道自动复制池分配；令牌可用数量与店铺池容量关联 |
| 26 | 06.09 | 按令牌、达人、目标渠道查询并分页；展示令牌、达人、目标渠道、目标店铺、使用量、有效分钟、过期时间、状态；可复制令牌 |
| 27 | 06.10 | 显示已用 / 可用总量、到期时间；状态「有效」「已用尽」「已过期」 |

布局参考进度表第 18、24、26 行的内嵌截图（店铺编码形如 `SHOP-100007`，容量显示「1 / 20000」并带刷新图标，满额标红）。

## 2. 现状（以代码为准）

- `crm_shop`：只有 `shop_name`、`weidian_shop_id`、`status`、`remark`、`copy_target`（任务 17b 的「默认复制店铺」，全公司最多一个）。`GET /admin-api/product/shops` 一次返回全部店铺，不分页、无筛选。
- 授权信息在 `integration` 模块（`WeidianShopView`：授权方式、状态、授权时间、令牌过期时间、最近检查结果），通过 `GET /admin-api/integration/weidian/shops` 单独返回；`POST …/shops/{id}/checks` 只做一次总的连通性检查。
- 对外复制接口（任务 17b）在 `ProductLinkService` 中：令牌未指定店铺时使用唯一的 `copy_target` 店铺。
- 复制令牌（任务 17a、28）：`crm_kol_copy_token` 已有 `target_channel`、`target_shop_id`、`quota`、`used`、`valid_minutes`、`expires_at`、`status`；默认额度 1 已在任务 28 修复。
- 订单同步：`POST /admin-api/order/syncs {shopId}` 按店铺触发，订单和退款一起同步。
- 店铺数量上限：系统配置 `shop.max-per-company`，默认 3。

## 3. 要做的事

### 3.1 店铺资料（06.01、06.02、06.05）

- `crm_shop` 新增：
  - `shop_code`：系统生成，格式 `SHOP-` + 6 位数字，每家公司从 `SHOP-100000` 起按创建顺序递增，公司内唯一，创建后不可修改；已有店铺按 `create_time` 回填；
  - `channel`：渠道，目前只有 `WEIDIAN`，新建时必填（下拉）；已有店铺回填 `WEIDIAN`；
  - `max_items`：最大商品数量，必填，默认 20000，范围 1～1000000；
  - `current_items`、`items_refreshed_at`：当前商品数及刷新时间（见 3.3）；
  - `is_source`：是否源店铺；
  - `copy_pool_status`：`JOINED`（已加入）/ `EXITED`（已退出）。源店铺固定显示「不参与（源店铺）」，不参与分配。
- 原 `copy_target` 迁移为复制池：`copy_target = true` 的店铺设为 `JOINED`，其余为 `EXITED`；迁移后删除 `copy_target` 列和 `PUT/DELETE …/{id}/copy-target` 接口，`CopyTargetShopApi` 改为按复制池分配（3.4）。
- 列表接口改为分页，按店铺编码（精确或前缀）、店铺名称（模糊）、渠道筛选；每行合并返回授权与接口状态（3.2），前端只调一个列表接口。
- 系统配置 `shop.max-per-company` 默认值由 3 改为 20（负责人 2026-10-21 决定）；上下限不变。
- 联动规则（06.05）：停用、未授权、授权过期、源店铺，都不能作为令牌目标店铺，也不参与自动分配；接口返回每个店铺「能否作为复制目标」及不能的原因，供前端显示。

### 3.2 授权与接口状态（06.03）

- 列表行返回授权状态（未授权 / 已授权 / 已过期）与授权过期时间，来自现有 `integration` 模块；跨模块只能经对方的 `api` 包。
- 「去授权」沿用 `POST /admin-api/integration/weidian/shops/{shopId}/authorizations`，不新增。
- 接口状态：把现有的「检查连接」扩展为分别检查商品、订单、退款三类接口，各自记录是否正常、检查时间和错误信息；用各类接口中最轻量的查询（例如只取 1 条）。列表展示三项状态。检查失败不影响其他两项。
- 检查结果存储位置由你决定（扩展现有授权表或新表），写进设计说明书。

### 3.3 商品容量（06.02）

- 「刷新容量」：调用微店商品列表接口取得店铺在售商品总数，写入 `current_items` 和 `items_refreshed_at`。走现有限流与重试；失败时保留旧值并返回错误。微店接口的具体字段待 M0 用真实账号核实，用模拟服务器测试，列入报告「待 M0 验证」。
- 复制成功（任务 17b 的复制流程每成功一个商品）时 `current_items` 加 1，使两次刷新之间的容量大致准确。
- 剩余容量 = `max_items - current_items`，不小于 0。

### 3.4 自动复制池与令牌（06.04、06.07～06.10）

- **可作为目标的店铺**：同一公司、渠道相同、启用、已授权且未过期、非源店铺、复制池状态为 `JOINED`、剩余容量大于 0。
- **渠道剩余容量** = 该渠道全部可作为目标的店铺的剩余容量之和。
- **生成令牌**：
  - 目标渠道必填（目前只有微店）；目标店铺选填，只能选可作为目标的店铺；
  - 指定店铺时，商品数量不得超过该店铺剩余容量；不指定时，不得超过渠道剩余容量；超出返回明确的错误码（登记到 `ErrorCode` 和两端语言包）；
  - 默认值来自系统配置（有效分钟 1440、商品数量 1），不写死。
- **自动分配**：令牌未指定店铺时，复制任务创建时（对外复制接口 `convert`）从该渠道可作为目标的店铺中，选**剩余容量最大**的一个作为本任务的目标店铺；一个任务只用一个店铺（`crm_copy_job.target_shop_id` 不变）。剩余容量不足以容纳本任务的商品数时，任务直接失败并给出容量不足的错误，不拆分到多个店铺。
- 并发：两个任务同时分配到同一店铺时不得超卖容量（锁定店铺行或用条件更新），写并发测试。
- 令牌列表补充目标店铺名称与编码（未指定时显示「自动分配」，任务创建后显示实际店铺），其余字段与状态现有实现已满足，复核时确认。

### 3.5 拉订单、拉退款（06.06）

- `POST /admin-api/order/syncs` 增加 `type`：`ORDER`、`REFUND`；不传时两者都同步（兼容现有调用）。权限码与审计不变。

## 4. 接口契约（与前端约定）

字段名用驼峰，ID 与金额为字符串，时间为 ISO-8601 UTC。

**`GET /admin-api/product/shops`**（`product:view`）

- 参数：`shopCode`、`shopName`、`channel`、`page`、`size`。
- 返回分页 `{items,total,page,size}`，每项：

| 字段 | 说明 |
|---|---|
| `id`、`shopCode`、`shopName`、`channel`、`weidianShopId`、`status`、`remark` | 基础资料；`status` 1 启用 0 停用 |
| `isSource` | 是否源店铺 |
| `copyPoolStatus` | `JOINED` / `EXITED` / `NOT_APPLICABLE`（源店铺） |
| `maxItems`、`currentItems`、`itemsRefreshedAt` | 容量 |
| `authStatus`、`authExpiresAt` | `UNAUTHORIZED` / `AUTHORIZED` / `EXPIRED` |
| `apiStatus` | `{ product: {ok, checkedAt, error}, order: {…}, refund: {…} }`，未检查时 `ok` 为 null |
| `copyEligible`、`copyIneligibleReason` | 能否作为复制目标；原因取值 `DISABLED`、`UNAUTHORIZED`、`AUTH_EXPIRED`、`SOURCE_SHOP`、`POOL_EXITED`、`CAPACITY_FULL` |
| `createTime`、`updateTime` | |

**新建 / 编辑店铺** `POST /admin-api/product/shops`、`PUT /{id}`（沿用现有的 `SUPER_ADMIN`）：`shopName`、`channel`、`weidianShopId`、`maxItems`、`remark`；`shopCode` 不可传。

**新增动作**（与现有店铺写操作一致，`SUPER_ADMIN`，写审计日志）：

| 接口 | 作用 |
|---|---|
| `PUT /{id}/source`、`DELETE /{id}/source` | 设为 / 取消源店铺 |
| `PUT /{id}/copy-pool`、`DELETE /{id}/copy-pool` | 加入 / 退出自动复制池（源店铺不可加入） |
| `POST /{id}/capacity-refreshes` | 刷新容量，返回更新后的店铺行 |
| `POST /{id}/api-checks` | 检查三类接口，返回更新后的店铺行（替代现有 `…/integration/weidian/shops/{id}/checks` 的用法；旧接口可保留） |

**`GET /admin-api/product/shops/copy-channels`**（`kol:copy-token`，与生成令牌相同）：`[{ channel, remainingCapacity, shops: [{ id, shopCode, shopName, remainingCapacity }] }]`，只含可作为目标的店铺。

**生成令牌** `POST /admin-api/kol/copy-tokens`：在现有字段上增加必填 `targetChannel`；`targetShopId` 选填；容量超出返回错误码。

**令牌列表**每项增加 `targetShopCode`、`targetShopName`（未分配时为 null）。

**`POST /admin-api/order/syncs`**：`{ shopId, type? }`。

## 5. 测试重点

- 店铺编码生成与回填（并发新建不重号、公司之间互不影响）；
- 复制池与源店铺的状态切换规则；不可作为目标的六种原因；
- 剩余容量计算、令牌数量校验、自动分配选剩余最多的店铺、容量不足失败、并发不超卖；
- `copy_target` 迁移结果（集成测试用含旧数据的库验证）；
- 三类接口检查互不影响；刷新容量失败保留旧值；
- 拉订单 / 拉退款按类型触发；
- 公司隔离与权限码（缺权限 403，商务数据范围）。

## 6. 不做

- 管理端页面（任务 37 前端）；
- 微店以外的渠道；
- 跨店铺拆分一个复制任务。
