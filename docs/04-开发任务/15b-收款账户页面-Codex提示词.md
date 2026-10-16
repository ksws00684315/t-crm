# 任务 15b（达人端 + 管理端）：收款账户页面

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **并行说明**：
> - 与 16b、18、19a、19b、20 同时进行。本任务只改前端，接口已由 15a 实现并合并。
> - 19b 也会在达人端新增导航，按第 4.3 节的导航约定各自加入口。
> - 20（站内消息）会在「达人提交收款账户」时通知商务，并链接到本任务的审核页 `/fund/payout-accounts?id={accountId}`，请支持这个查询参数（第 4.4 节）。

---

## 1. 背景

任务 15a 已实现收款账户的后端：

- 达人本人填写 PayPal 或 USDT 账户；
- 所属商务审核，超管也可以审核；
- 账号加密保存，列表中脱敏显示。

本任务做两端的界面。客户要求：

- 收款账户由达人本人填写，不能由商务代填；
- 达人操作越少越好（2026-10-14 会议）。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点：02 第 7 节、05、06 第 3 节）。
2. 设计说明书：
   - §3.1 权限码 `payout-account:review`、`commission:view`；
   - §4.11 收款账户；
   - §6.2、§6.3 收款账户与文件接口。
3. `docs/04-开发任务/15a-收款账户-后端-执行报告.md`：接口字段、错误码、脱敏规则。
4. `docs/04-开发任务/06e-管理端布局与交互体验-执行报告.md` 第 2 节：管理端新页面必须使用 `ListSearch`、`DataTable`、`CopyText`。
5. 现有代码：
   - 达人端：`layout/index.vue`、`router/routes.ts`、`api/`、`locales/`、`views/account`；
   - 管理端：`router/routes.ts`、`utils/permission.ts`（`meta.permission` 的写法）、`utils/http`（下载与 blob）、`locales/`。

## 3. 执行环境与交付

- 在独立的 git 工作树中工作，分支 `task/15b-payout-account-ui` 已从最新的 `main` 检出。
- **只修改 `kol-portal/`、`admin-ui/` 和你的执行报告。**
- 两个前端都执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`，全部通过。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 已定的设计决定（不要自行改变）

### 4.1 达人端「收款账户」页面

1. 路由 `/payout-accounts`，需要登录。
2. **列表**：调用 `GET /kol-api/payout-accounts`，每个账户一张卡片，显示：
   - 类型（PayPal / USDT + 网络）；
   - 完整账号或地址：本人可见，使用复制按钮；
   - 收款人（PayPal）；
   - 状态标签（待审核 / 已通过 / 已驳回）、默认标记；
   - 驳回原因（`REJECTED` 时醒目显示）；
   - 提交时间。
3. **新建**（达到 5 个时禁用，并提示上限）：
   - 先选类型；
   - **PayPal**：账号（≤ 128）、收款人（必填，≤ 64）；
   - **USDT**：网络下拉（TRC20 / ERC20 / BEP20 / SOL / TON）、地址（字母和数字，≤ 128）、二维码（可选：先调用 `POST /kol-api/files`，`bizType=PAYOUT_QR`，前端预检 PNG/JPEG 与 2MB，最终以后端为准；上传成功后显示缩略图，可以移除重传）。
   - 提交调用 `POST /kol-api/payout-accounts`，成功提示「已提交，等待商务审核」。
4. **修改**：只有 `PENDING`、`REJECTED` 的账户有「修改」，提交后回到待审核。409 `PAYOUT_ACCOUNT_LOCKED` 时提示对应文案并刷新。
5. **设为默认**：只有 `APPROVED` 且非默认的账户显示。
6. **停用**：二次确认，文案「停用后该账户不能恢复，也不会再显示。」成功后刷新。
7. **二维码查看**：已有二维码时可点击查看大图。通过 `GET /kol-api/files/{id}` 取 blob 后显示，不把令牌放进 URL。
8. **空状态**：说明「添加 PayPal 或 USDT 账户，审核通过后用于收款」，并提供新建按钮。
9. 375px 宽度下不横向溢出，对话框在手机上全屏或接近全屏。
10. 首页加一个「收款账户」入口；19b 也会在首页加入口，冲突时保留双方。

### 4.2 管理端「收款账户审核」页面

1. 新增菜单分组「资金」，路由 `/fund/payout-accounts`。
   - 可见条件：有 `payout-account:review` 或 `commission:view` 任一权限；
   - 如果 `meta.permission` 目前只支持单个权限码，在 `utils/permission.ts` 中扩展为「数组表示任一满足」，并补测试，不影响现有路由。
2. **列表**：`GET /admin-api/commission/payout-accounts`。
   - 筛选：状态（默认「待审核」，可选「全部」）、达人编号、类型；
   - 列：达人编号与姓名、类型与网络、脱敏账号、收款人、状态、默认、提交时间、审核时间、操作；
   - 数据范围由后端控制。
3. **详情抽屉**（需要 `payout-account:review`）：
   - 调用 `GET .../{id}`，显示完整账号（`CopyText`）；
   - 有二维码时通过 `GET /admin-api/files/{id}` 取 blob 显示图片；
   - 抽屉中注明「查看详情会记录操作日志」。
4. **审核**（详情抽屉内，状态为 `PENDING` 时）：
   - 「通过」：二次确认；
   - 「驳回」：必填原因，≤ 255；
   - 请求体必须带详情接口返回的 `lastSubmittedAt` 原值；
   - 409 `PAYOUT_ACCOUNT_NOT_PENDING` 时提示「账户已被修改或已审核，请刷新后重试」，并自动重新加载详情。
5. **待审核数量**：有 `payout-account:review` 时，在「收款账户审核」菜单项旁显示 `GET .../pending-count` 的数量徽标（0 时不显示）。进入页面和审核后刷新；另外每 60 秒刷新一次，浏览器标签页不可见时暂停。如果当前布局的菜单不支持徽标，改为在页面标题旁显示，并在报告中说明。
6. 只有 `commission:view` 的账号：只能看列表，不显示详情和审核。

### 4.3 达人端导航约定（与 19b 共用，不得改动）

达人端顶部导航的菜单项集中定义在 `kol-portal/src/layout/nav.ts`：

```ts
export interface NavItem {
  name: string; // route name
  path: string;
  title: string; // locale key
}

export const NAV_ITEMS: NavItem[] = [
  { name: "Home", path: "/", title: "nav.home" },
  { name: "PayoutAccounts", path: "/payout-accounts", title: "nav.payoutAccounts" }, // 15b
  { name: "Orders", path: "/orders", title: "nav.orders" } // 19b
];
```

- `layout/index.vue` 在页头用水平菜单渲染 `NAV_ITEMS`，375px 宽度下不溢出（窄屏可以只显示图标，或改为下拉菜单）。
- 开工时如果 19b 尚未合并，按同一约定创建这个文件和渲染逻辑，只放「首页」和本任务的一项；合并冲突时保留双方的条目。
- 语言包键 `nav.home`、`nav.payoutAccounts`、`nav.orders` 中英文都要有。

### 4.4 与站内消息（20）的约定

管理端审核页支持查询参数 `?id={accountId}`：打开页面时如果带了该参数，并且当前账号有 `payout-account:review`，就直接打开该账户的详情抽屉。

### 4.5 不在本任务范围

- 后端改动（15a 已完成）。
- 提现、预付页面（以后任务）。
- 站内消息（20）。

## 5. 执行步骤

1. 两端的类型与接口封装。
2. 达人端导航（按第 4.3 节约定）与收款账户页面。
3. 管理端菜单、审核页、待审核数量徽标。
4. 语言包、测试、执行报告。

## 6. 测试要求

| 场景 | 期望 |
|---|---|
| 达人端表单 | 两种类型的字段与校验；网络枚举；二维码类型与大小预检；达到 5 个时禁用新建 |
| 达人端操作 | 修改只对 `PENDING` / `REJECTED` 显示；设默认只对 `APPROVED` 非默认显示；停用二次确认；409 文案 |
| 管理端权限 | 无两个权限码时看不到菜单；只有 `commission:view` 只能看列表；有 `payout-account:review` 能看详情和审核 |
| 管理端审核 | 请求体带 `lastSubmittedAt` 原值；驳回原因必填；409 后重新加载；`?id=` 自动打开详情 |
| 徽标 | 数量为 0 不显示；页面不可见时暂停轮询 |
| 国际化 | 两端新键在中英文语言包中一致；残留中文测试通过 |

**浏览器验证**（写进报告并附截图）：

- 本地后端与两端连通：达人新建 USDT（含二维码）和 PayPal 各一个；
- 商务看到待审核数量为 2；
- 打开详情查看完整账号与二维码，通过一个、驳回一个；
- 达人看到驳回原因，修改后重新提交；
- 截图：达人端中文、英文、375px 各一张；管理端列表、详情、徽标各一张。

## 7. 文档与报告

执行报告写在 `docs/04-开发任务/15b-收款账户页面-执行报告.md`：页面与权限对照、测试清单、浏览器验证与截图、偏离与遗留。

## 8. 禁止事项与停止条件

**禁止**：

- 修改 `backend/`、`redirect/`、`deploy/`；
- 新增依赖；
- 把完整账号写进日志、URL 或本地存储；
- 删除或弱化测试；
- 推送 `main` 或合并 PR；
- 使用 `push --force`、`reset --hard`。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 接口实际字段与 15a 的执行报告不一致；
- 验证在合理排查后仍失败。
