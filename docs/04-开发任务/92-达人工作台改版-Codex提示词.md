# 任务 92：达人工作台改版

> **执行环境**：本机。
> - **前置**：任务 88（PR #165，改达人端）和任务 90（改可提现、冻结算法）合并后才开始。工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/92-kol-workspace-redesign`，分支 `task/92-kol-workspace-redesign`，从最新 `main` 检出。
> - 后端端口 `TCRM_ADMIN_PORT=18816`，达人端 Vite `8885`（`VITE_KOL_API_TARGET=http://localhost:18816`）。连接共享中间件（`deploy/.env.local` 只读），测试数据前缀 `t92_`。
> - 改达人端、后端一个只读接口和文档。不新增迁移，不新增依赖（`echarts` 已在 `kol-portal/package.json`）。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - 规范 03（接口）、规范 11 第 3、4 节（ECharts、`ChartPanel`）、规范 12；
> - 规范 13 第 2、3.6、4 章和第 6 章；
> - 设计说明书 §4.8 的达人五层金额、`/kol-api/commissions/overview` 接口说明；
> - [ADR-032](../06-架构决策/ADR-032-佣金结算与提现模型.md) 附录 B。

## 1. 背景

本需求来自甲方反馈，负责人 2026-10-31 确认，本文即需求登记（规范 12）。下文与现有页面不同之处，以本文为准。设计稿是负责人私有链接，执行时看不到，版式以本文 §2 的描述为准。

现状：`views/home/index.vue` 是近 7 天四张指标卡加手写 SVG 折线；`views/funds/index.vue` 顶部有完整的收入汇总。

## 2. 工作台要求

页面只保留主标题、「截至」时间和刷新按钮，从上到下三个板块。移除现有近 7 天四张指标卡、提现概览卡和手写 SVG。

### 2.1 第一板块：总收入

不筛选，展示当前情况。两张卡并排，收入卡和可提现金额卡按约 3:2 分配宽度，不写死像素宽度，随屏幕等比变化，窄屏上下排列。

**收入卡**：沿用现有资金页「收入构成」的版式（`views/funds/index.vue`，资金页删除后移到这里）。白底卡，左侧色块放预估总收入和小字（浅底用 `--tcrm-color-order-band` 同样的 8% 主色混合）；右侧两行列表。色块和列表按 2:3 分配卡内宽度。列表行首圆点（佣金蓝、预付绿），标签用 16／24px、字重 600，标签和小字靠左，金额靠列表右端，行间分隔线。窄屏时左右改为上下排列。

| 项目 | 金额 | 二级说明 |
|---|---|---|
| 预估总收入（首要金额字号、`primary-text`） | 后两项之和 | 预估总佣金收入 + 已收预付款 |
| 预估总佣金收入（明细金额字号） | `estimated − deducted − pendingDeduction`，为负时显示红色负数（沿用资金页 `.negative`） | 已扣除从佣金扣回的预付款，佣金不足时显示为负数 |
| 已收预付款（明细金额字号） | `income.prepayPaid` | 已预付款项，约定佣金抵扣金额将在佣金收入中扣除 |

**可提现金额卡**：绿色高亮（文字 `success`、浅底 `success-bg`、`success` 边框）。金额 `income.available`，用首要金额字号，为负时显示红色负数。二级说明「已结算佣金金额 − 已扣回预付款」。金额和说明靠左，「申请提现」主按钮高 40px、文字 16／24px、左右内边距 20px，紧跟金额区右侧、间距 32px、垂直居中，不贴卡片右边缘，跳 `/funds?apply=1`。窄屏时换到金额下方。

**负责人决定**：第一板块的小字固定，不写具体金额。可提现金额的公式是已扣回，不是剩余待扣回：`available = (available + deducted) − deducted`，`pendingDeduction` 不从已结算佣金里减。预估总佣金收入 = 销售额 × 佣金比例 − 抵扣预付款，抵扣预付款含已扣回和待扣回两部分；不抵扣的预付计入预估总收入。已收预付款是已支付的预付款。没有预付时已收预付款仍显示 ¥0.00，公式保持成立。

第一板块的预估总佣金收入减过预付款，第二板块的预估总佣金没有减，两处金额不同，这是负责人确认的展示，不是缺陷。

**措辞**：§2 和 §6 的中文文案是负责人定稿，逐字使用，不自行改写。新增文字用正式书面语，不用「冲抵」。英文按 §6 原文。

### 2.2 第二板块：佣金明细

不筛选，一律累计金额，标题右侧写「累计金额」，标题下方小字「仅计算佣金金额，不含预付款抵扣」。

- 第一行三项靠左排列，间距 24px，不拉开到两端。销售额（蓝色 `primary-text`），金额下放小字「推广订单实际支付金额，未扣除退款」→ 小标签「× 当前佣金比例 12%」→ 预估总佣金（`income.estimated`），金额下放小字「销售额 × 佣金比例，未抵扣预付款」。
- 第二行四张等宽小卡，顺序固定，每张卡金额下放一行定义小字：

| 卡片 | 取值 | 定义小字 | 卡底按钮 | 跳转 |
|---|---|---|---|---|
| 已结算佣金金额（绿，文字 `success`、浅底 `success-bg`） | `income.available + income.deducted` | 已结算且未申请提现的佣金，未抵扣预付款 | 我的订单 | `/orders` |
| 冻结中（琥珀） | `income.frozen` | 未结算订单的佣金金额 | 我的订单 | `/orders` |
| 提现中（中性） | `income.occupied` | 已提交提现申请待打款的金额 | 我的申请 | `/funds` |
| 已提现（中性） | `income.commissionPaid` | 已完成打款 | 我的申请 | `/funds` |

- 卡底按钮量感要小：高 32px，按文字宽度，左对齐，不通栏，四张卡的按钮底部对齐。手机可点击区域仍按规范 13 不小于 44px。

- **四张卡相加必须等于 `income.estimated`**。为了四卡不重复计算，已结算佣金金额只算未申请提现的部分，即已结算入账佣金 − 提现中 − 已提现。取值用分运算，不减扣回的预付款。依据：钱包余额 = 已释放 − 已抵扣 − 已付（ADR-032 附录 B）。
- 资金页收到 `?apply=1` 时打开申请弹窗，打开后清掉这个查询参数。
- 底部小字：「以上四项合计等于预估总佣金。历史订单按下单时的佣金比例计算。」
- 佣金比例用账号资料的 `commissionRate`，字号用正文，不是金额字号。为空时显示已有文案「待配置」。不用销售额和佣金反算比例。
- 本板块不显示预付金额。

### 2.3 第三板块：销售趋势

- 柱状图显示 GMV（左轴），折线显示佣金（右轴）。悬停提示显示日期、GMV、佣金、订单数。提示里注明销售额为支付金额、未扣退款（先核对 `pay_amount` 确实如此）。
- 筛选项：近 7 天、近 30 天（默认）、按自然月、日期范围。旁边标注「北京时间」。
- 近 N 天包含今天。边界一律按 `+08:00` 传给 `/kol-api/analytics/overview`。
- 日期范围最长 366 天，超出时前端提示并阻止查询，后端现有上限不动。
- **粒度**：近 7 天、近 30 天按日；自然月固定按日（含 31 天的月份）；日期范围在 30 天以内按日，超过 30 天按月。按月时首尾月只统计范围内的部分，标签显示 `2026-10`。
- 接口 `daily` 只返回有订单的日期，前端要补齐为 0 的日期，再按粒度汇总。金额汇总用 `utils/money` 的分运算，不用浮点数累加。
- 上方不再放汇总金额卡，图例显示所选时段的 GMV 和佣金合计。

### 2.4 图表组件

达人端新增 `components/ChartPanel.vue` 和 `utils/echarts.ts`，职责与规范 11 的管理端版本相同：唯一创建实例、随容器自适应、卸载销毁、颜色和字号读 token、加载中／无记录／加载失败状态齐全。按需引入 ECharts，页面不直接调 `echarts.init`，不用 Vue 封装包。规范 11 里补上达人端组件的位置。

## 3. 累计销售额接口

`getCreatorAnalytics` 有 366 天上限，不能用来算累计。

- 新增 `GET /kol-api/analytics/sales-total`，放在 `KolAnalyticsController`，`@SaCheckLogin(type = StpKolUtil.TYPE)`，返回 `{salesCny, asOf}`，`salesCny` 序列化为字符串。
- 只从登录态取达人身份。统计口径与 `KolAnalyticsService.overview` 相同：本人、`ATTRIBUTED` 订单、本人商品行的 `pay_amount`。
- 用 SQL 聚合，不要把全部订单读进内存。
- 更新设计说明书的接口清单。

## 4. 资金页

`views/funds/index.vue` 删除收入构成、佣金汇总卡和资金说明，改为顶部一条：当前可提现金额加「申请提现」按钮。资金记录、提现记录、申请弹窗保留不变。删除因此废弃的计算属性、样式和语言包条目。

提现条件不足时的提示逻辑不改。

## 5. 字号与规范

规范 13 第 2 章和 `docs/03-设计/网页字体字号规范.md` 新增两个文字角色，`tokens.css` 新增对应变量（两端都加，保持一致）：

| 角色 | 字号／行高 | 字重 | 用于 |
|---|---|---|---|
| 首要金额 | 36／44px | 600 | 第一板块预估总收入、可提现金额 |
| 明细金额 | 24／32px | 600 | 第一板块预估总佣金收入、已收预付款，第二板块金额 |

手机宽度下金额不得溢出容器；需要缩小时写进规范 13，并在报告里说明。

## 6. 文案

中英文都放语言包，英文如下，可微调但意思不变：

| 中文 | English |
|---|---|
| 预估总收入 | Estimated total income |
| 预估总佣金收入 + 已收预付款 | Estimated commission income + advances received |
| 预估总佣金收入 | Estimated commission income |
| 已扣除从佣金扣回的预付款，佣金不足时显示为负数 | Net of advances repaid from commission. Shown as negative if commission is insufficient. |
| 已收预付款 | Advances received |
| 已预付款项，约定佣金抵扣金额将在佣金收入中扣除 | Amounts already advanced. Agreed repayments are deducted from commission income. |
| 佣金明细 | Commission details |
| 累计金额 | Cumulative amounts |
| 仅计算佣金金额，不含预付款抵扣 | Commission only, excluding advance repayment |
| 推广订单实际支付金额，未扣除退款 | Amount paid on promoted orders, before refunds |
| 销售额 × 佣金比例，未抵扣预付款 | Sales × commission rate, before advance repayment |
| 当前佣金比例 | Current commission rate |
| 已结算佣金金额 | Settled commission |
| 已结算且未申请提现的佣金，未抵扣预付款 | Settled commission not yet requested, before advance repayment |
| 未结算订单的佣金金额 | Commission on unsettled orders |
| 已提交提现申请待打款的金额 | Withdrawal requests awaiting payment |
| 已完成打款 | Payment completed |
| 申请提现 / 我的订单 / 我的申请 | Withdraw / My orders / My requests |
| 可提现金额 | Withdrawable amount |
| 已结算佣金金额 − 已扣回预付款 | Settled commission − advances repaid |
| 以上四项合计等于预估总佣金。历史订单按下单时的佣金比例计算。 | The four amounts above total the estimated commission. Past orders use the commission rate in effect when they were placed. |
| 按自然月 / 日期范围 | By month / Date range |

## 7. 不做

- 第一、二板块不加时间筛选。
- 不改佣金、预付、提现的计算。
- 不做管理端改动（`tokens.css` 新增变量除外）。
- 不修改 `docs/04-开发任务/README.md`。

## 8. 测试

- 后端：`sales-total` 单元测试覆盖只算本人、只算 `ATTRIBUTED`、无订单返回 `0.00`；集成测试 `*IT` 标注 `@Tag("docker")`，验证 SQL 和跨达人隔离。
- 前端：第一板块在无预付、不抵扣预付、部分已扣回、佣金不够扣四种情况下的取值；第二板块四张卡之和等于 `estimated`；可提现金额等于已结算佣金金额减 `deducted`；各卡底按钮的跳转目标；比例为空；`available` 为负。
- 趋势：补零、31 天自然月按日、31 天日期范围按月、跨年按月、366 天上限、ECharts 配置（mock `utils/echarts.ts`，规范 11）。
- 资金页：`?apply=1` 打开弹窗并清参数；汇总已移除；原有提现流程测试继续通过。
- 原有断言只能为本任务的行为变化调整，每处调整在报告里说明。

## 9. 验收与交付

- **浏览器验收**（规范 12 第 5 节）：1440×900、1280×800、2560 宽、390×844、375×812，中英文；覆盖有数据、空数据、加载失败、比例为空、可提现为负、长金额（千万级）。
  - 优先用 `t92_` 达人连共享中间件。缺订单或资金数据时可以在浏览器里模拟接口，报告里写明哪些是模拟的。
- **收尾**：删除 `t92_` 数据，停掉自己启动的进程。
- **检查**：达人端六项 `pnpm install --frozen-lockfile`、`lint`、`typecheck`、`test`、`build`、`notices:check`；后端 `mvn verify`、`mvn verify -Pdocker-it`；管理端因 `tokens.css` 变动跑 `lint`、`typecheck`、`test`、`build`。检查、需求复核、浏览器验证交给子代理。
- **执行报告**：`docs/04-开发任务/92-达人工作台改版-执行报告.md`，逐条复核表覆盖 §2～§6 每一项，状态只用「满足 / 部分满足 / 不满足 / 已不存在」。
- **PR**：提交 PR，不合并、不推 main。推送后看一次 `gh pr checks` 的结果写进报告，不轮询等待。

## 10. 停下来报告

- 任务 90 合并后接口字段或 `available` 含义与本文不一致，或四张卡之和不再等于 `estimated`；
- `pay_amount` 不是未扣退款的支付金额；
- 需要新增依赖、迁移，或改动佣金计算才能完成；
- 合理排查后验证仍失败。
