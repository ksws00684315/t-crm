# 任务 87：管理端遗留问题修复

> **执行环境**：本机。
> - 工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/87-admin-ui-carryover`，分支 `task/87-admin-ui-carryover`，从最新 `main` 检出。
> - 后端端口 `TCRM_ADMIN_PORT=18811`（只用于浏览器验收），管理端 Vite `8881`。连接共享中间件（`deploy/.env.local` 只读），测试数据前缀 `t87_`。
> - 只改管理端和文档，不改后端，不新增迁移，不新增依赖。可与任务 88、89 并行。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [`遗留问题清单.md`](遗留问题清单.md) 中本任务涉及的条目原文；
> - 规范 13 第 4.1、4.3、4.5 节，第 6 章（窄屏），附录 A；规范 11 中列表和弹窗的小节。

## 1. 背景

负责人 2026-10-31 决定全盘处理遗留问题。本任务处理管理端部分。下表的「核对结果」是审查人 2026-10-31 读代码得到的，动手前再确认一次；发现问题已经不存在时，不要硬改，在报告里写明证据，并把该条标记为「已不存在」。

## 2. 逐条要求

| 编号 | 问题 | 核对结果与要求 |
|---|---|---|
| L-006 | 375 宽下岗位模板卡片「保存」被裁剪 | 代码里已搜不到 420px 最小宽度，可能已随权限页重做消失。先在 375 宽实测 `views/system/post-template`，能复现才改 |
| L-007 | 375 宽下「共 N 条」折成三行 | `DataTable.vue` 的 `.data-table__total` 没有 `white-space: nowrap`。补上，再核对窄屏工具栏不溢出 |
| L-014 | 达人列表固定列编号被省略；任务 59 截图过时 | 达人列现为 `NameCode` 的 `kol` 列，`minWidth: 170`。实测长编号（如 `KOL-MUGO…`）是否被省略，被省略才加宽。任务 59 的旧截图是历史记录，**不重拍** |
| L-015 | 结算明细的店铺筛选要手填 ID | `views/fund/settlement-details/index.vue` 的 `shopId` 改为店铺下拉，复用 `api/product/shop.ts` 现有的店铺选项接口（全量 `List` 接口，不得用 `size=100` 冒充），显示遵守名称+编码规则 |
| L-018 | 订单链路行级佣金块无权限时整块隐藏 | **负责人已决定：加提示**。`views/order/chain/index.vue` 的 `v-if="line.commission"` 分支补说明，无 `commission:view` 与 OWN 范围不满足两种情况要区分，文案和订单级资金卡片（`flowsView` 的 `noPermission` / `scopeLimited`）一致 |
| L-019 | 链路资金区不显示账务状态 | **负责人已决定：显示**。接口已返回 `funds.accountStatus`（`POSTED` / `UNVERIFIED`），语言包已有 `finance.accountStatus`。在资金区加状态标签，只在有 `funds` 时显示；两个状态的文案若缺，中英文都补 |
| L-021 | 不认识的通知类型直接显示键名 | `components/NotificationText.vue` 用 `notification.type.${item.type}` 拼键。未知类型回退到通用文案（新增语言键），铃铛和消息列表都覆盖，加单元测试 |
| L-025 | 选择导入类型单选项没有竖排 | `views/system/import-task/index.vue` 的 `.upload-type-group` 只作用在外层 div。让 `el-radio-group` 自身纵向排列 |
| L-026 | 导入步骤文案只写 `.xlsx` | `ImportDialog.vue` 同时接受 `.csv`。改语言包 `stepUpload`（中英文），只在确实接受 CSV 的导入类型上提到 CSV；不改接受的文件类型 |
| L-028 | 语言包有无引用的键 | `finance-zh-CN.ts`、`finance-en-US.ts` 的 `applicationType`，以及 `route.payoutAccounts`（任务 82 删除审核页签后无引用）。全仓搜索确认无引用后删除，包括动态拼键的情况 |
| L-030 | 子菜单展开后没有滚动条，下方菜单点不到 | `.layout__aside` 是 `overflow: hidden`，`layout/SideMenu.vue` 的 `.side__menu` 没有滚动。按清单建议给 `.side__menu` 加 `flex: 1`、`min-height: 0`、`overflow-y: auto`，确认外层是 flex 列。窄屏抽屉模式也要实测 |

## 3. 不做

- 不改后端和接口，不改 `DataTable` 高度逻辑。
- 不改 L-029：相邻页签已在任务 82 删除，问题不再存在，已由架构负责人关闭。
- 不重拍任务 59 截图。
- 不修改 `docs/04-开发任务/README.md`。

## 4. 测试

- L-015、L-018、L-019、L-021、L-028 必须有单元或组件测试；修缺陷先写失败的测试。
- 纯 CSS 的 L-007、L-025、L-030 以浏览器实测为准，不强求单元测试。
- 原有断言只能为本任务的行为变化调整，每处调整在报告里说明。

## 5. 验收与交付

- **浏览器验收**（规范 12 第 5 节）：1440×900、1280×800、2560 宽，中英文；L-006、L-007 加 375×812，L-030 加窄屏抽屉。L-018 用超管、只有 `commission:view` 的账号、无该权限的账号、OWN 商务四种身份验证。共享库没有对应数据时，在报告里写明证据边界。
- **收尾**：删除 `t87_` 数据，把改过的配置恢复默认，停掉自己启动的进程。
- **检查**：admin-ui 六项 `pnpm install --frozen-lockfile`、`lint`、`typecheck`、`test`、`build`、`notices:check`。检查、需求复核、浏览器验证交给子代理。
- **执行报告**：`docs/04-开发任务/87-管理端遗留问题修复-执行报告.md`，逐条复核表覆盖本文 §2 每一项，状态只用「满足 / 部分满足 / 不满足 / 已不存在」。
- **遗留清单**：不改状态，由架构负责人在合并时更新。
- **PR**：提交 PR，不合并、不推 main。推送后看一次 `gh pr checks` 的结果写进报告，不轮询等待。

## 6. 停下来报告

- 需要改后端或接口才能完成某一条；
- 某一条的改法需要业务决定（不要自行决定）；
- 合理排查后验证仍失败。
