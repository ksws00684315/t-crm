# 任务 24（前端与文档）：治理审查整改

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：本机执行，独立工作树 `.worktrees/24-frontend-governance`，分支 `task/24-frontend-governance` 已从最新的 `main` 检出。
>
> **并行说明**：与任务 23（后端工程质量加固，非本机执行）同时进行。任务 23 会在两个前端的语言包中新增错误码 `COMMON_UNSUPPORTED_MEDIA_TYPE`，并修改设计说明书 §5.2、§6.1～§6.3。本任务不要改这些章节；语言包冲突时保留双方。

---

## 1. 背景

任务 22 阶段性治理审查（[报告](22-阶段性治理审查-报告.md)，PR #67）中的前端与文档问题由本任务处理，后端问题由任务 23 处理。

## 2. 开始前必读

1. `AGENTS.md`、`docs/05-工程规范/`（重点 02 第 7 节、05）。
2. 任务 22 报告第 3、4、5 节。
3. ADR-015（中英文）、ADR-018（权限码）、任务 06d 执行报告中关于术语的遗留。
4. 现有代码：
   - `admin-ui/src/composables/useVisibilityPolling.ts`、`admin-ui/src/stores/payout-pending.ts`；
   - `admin-ui/src/router/__tests__/component-names.spec.ts`；
   - 两个前端的 `locales/en-US.ts`；
   - 用户管理页的权限勾选对话框，以及 `GET /admin-api/system/permissions` 返回的权限目录。

## 3. 执行环境与交付

- **只修改 `admin-ui/`、`kol-portal/`、第 4.5 节列出的文档，以及你的执行报告。**
- Node 24（CI 用的版本；Node 20 下 vitest 会失败）。两个前端都执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm notices:check`，全部通过。
- 本机验证端口：管理端 Vite 8824，达人端 Vite 8825；需要本地后端时用后端 18241、PostgreSQL 55441、Redis 46441，Compose 项目名 `tcrm-24`。避开负责人正在用的 18081、8848、8849、55432、46391。验证完停掉进程并 `down -v`。
- 推送分支，按 PR 模板创建 PR，用 `gh pr checks --watch` 等 CI 通过。**不要合并 PR，不要推送 `main`。**
- **不新增依赖。**

## 4. 要做的事

### 4.1 统一「标签页隐藏时暂停」的轮询（报告 P2-04）

现在有两套实现：

- `useVisibilityPolling`（组件内使用，消息铃铛）；
- `stores/payout-pending.ts` 自己写的定时器与 `visibilitychange`（收款账户待审核徽标）。

改为一套：

- 抽出一个不依赖组件生命周期的工具，例如 `createVisibilityPoller(load, intervalMs)`，返回 `start` / `stop`；
- `useVisibilityPolling` 基于它实现；
- `payout-pending` store 改用它。

行为不变：立即执行一次；按间隔轮询；标签页隐藏时暂停；回到前台立即执行一次。原有测试全部保留并通过，为新工具补单元测试。

### 4.2 `component-names.spec.ts` 偶发超时（报告 P2-06）

这个测试会动态加载每一个可缓存的页面组件，在测试并行运行时偶尔超时。

- 先找出慢的原因（逐个计时，确认是哪类页面或依赖导致加载慢）；
- 改为不依赖长超时也稳定的写法，例如对每个路由单独写一个用例，或在不影响断言的前提下减少重复加载；
- 不得删除断言，不得把超时放得更宽；
- 在本地连续完整运行 `pnpm test` 10 次都通过，并在报告中记录。

### 4.3 英文界面术语统一（报告 P2-12）

英文界面中「达人」有时译作 KOL，有时译作 Creator。统一为 **Creator**：

- 范围：`admin-ui/src/locales/en-US.ts`、`kol-portal/src/locales/en-US.ts` 中面向用户的文案；例如「KOL code」改为「Creator code」，「KOL list」改为「Creators」；
- 不改：中文文案、代码中的标识符、路由路径、权限码（如 `kol:view`）、接口字段名；
- 统一后在两份英文语言包中搜索 `KOL`、`Kol`、`kol`，确认面向用户的文案里不再出现（键名可以保留）；
- 更新受影响的测试断言。

### 4.4 只授 `kol:edit` 不授 `kol:view` 的情况（报告 P3-04）

在用户管理的权限勾选中，编辑权限依赖对应的查看权限才有意义。

- 依赖关系：
  - `kol:edit` → `kol:view`
  - `product:edit` → `product:view`
  - `product:status` → `product:view`
  - `order:manage` → `order:view`
  - `payout-account:review` → `commission:view`
- 勾选编辑类权限时，自动勾上它依赖的查看权限；取消查看权限时，一并取消依赖它的编辑类权限。在对话框中用一行说明文字告知用户。
- 只改前端交互，后端校验不变。
- 补测试覆盖两个方向的联动。

### 4.5 文档（报告 P3-01、P3-03、P3-06）

- `admin-ui/README.md`、`kol-portal/README.md`（没有就在根 `README.md` 的开发说明中）：写明本地开发与测试需要 Node 24，Node 20 下 vitest 会失败。
- `deploy/README.md`：在上线检查清单中加入「生产环境禁止开启 `crm.mail.log-links`（会在日志中记录收件人与链接）」。
- 设计说明书 §3.1：说明「岗位模板只决定新建子账号时预填的权限码，之后可以任意增减；『运营可查看全部达人但不能编辑』这类约定靠超管配置来保证，系统不强制」。

## 5. 测试要求

| 场景 | 期望 |
|---|---|
| 轮询工具 | 立即执行一次；按间隔执行；隐藏时暂停；可见时立即执行；`stop` 后不再执行 |
| 消息铃铛、待审核徽标 | 原有测试全部通过 |
| 组件名测试 | 不依赖长超时，连续 10 次完整运行都通过 |
| 英文术语 | 两份英文语言包面向用户的文案中不再出现 KOL |
| 权限联动 | 勾选编辑类自动勾选查看类；取消查看类一并取消依赖它的编辑类 |
| 国际化 | 新增的键在中英文语言包中一致；残留中文测试通过 |

浏览器验证（写进报告并附截图）：

- 管理端切换为英文后，达人列表、达人详情、转移记录、订单列表、收款账户审核页的术语；
- 达人端英文首页、我的订单、收款账户；
- 用户管理的权限勾选联动。

## 6. 执行报告

写在 `docs/04-开发任务/24-前端与文档治理-执行报告.md`：逐项说明、慢测试的原因分析、测试与截图、偏离与遗留。

## 7. 禁止事项与停止条件

**禁止**：

- 修改第 3 节范围以外的文件，包括 `backend/` 和设计说明书中第 4.5 节以外的章节；
- 新增依赖；
- 删除或弱化测试，放宽超时；
- 推送 `main`、合并 PR；
- 使用 `push --force`、`reset --hard`、改写已推送历史。

**遇到以下情况停下来**（推送已完成的提交，以 `[WIP]` 标题创建 PR，在报告中说明）：

- 术语统一时，遇到无法判断该用 Creator 还是保留原词的情况（例如与客户沟通中固定使用的词）；
- 权限目录中的依赖关系与第 4.4 节不一致；
- 合理排查后验证仍失败。

完成后回复一段总结：PR 链接、各项结果、测试结果、遗留。
