# 任务 58：达人端视觉对齐管理端（SparkTrack 同一套视觉）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/58-kol-visual-align`，分支 `task/58-kol-visual-align`，已从最新 `main` 检出。达人端 Vite `8858`，联调用的后端端口 `TCRM_ADMIN_PORT=18581`。只改 `kol-portal/`、`admin-ui/src/style/__tests__/`（两端令牌的契约测试）和规范文档；后端不动，不新增迁移。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，以及规范 10、11 和 `docs/03-设计/网页字体字号规范.xlsx`。

## 1. 背景与负责人决定（2026-10-24）

负责人要求：**达人端要和管理端看起来是同一个产品**，使用同一套 SparkTrack 配色、圆角、卡片和列表样式。**手机端本期不考虑。**

架构负责人核对的现状：

- **字体排版已对齐**：字号令牌两端一致，`admin-ui/src/style/__tests__/tokens.spec.ts` 会扫描 `kol-portal/src` 中写死的字号。
- **视觉令牌缺失**：`admin-ui/src/style/tokens.css` 有 81 个 `--tcrm-*` 变量，`kol-portal/src/style/index.css` 只有 37 个。缺少：
  - 颜色：`--tcrm-color-primary`、`-primary-text`、`-on-primary`、`-text`、`-text-secondary`、`-muted`、`-border`、`-card`、`-page`、`-success(-bg)`、`-warning(-bg)`、`-danger(-bg)`；
  - 圆角：`--tcrm-radius-card`、`-control`、`-tag`；
  - 阴影：`--tcrm-shadow-overlay`；
  - 布局：`--tcrm-header-height`、`--tcrm-sidebar-width`、`-collapsed`。
  
  因此达人端的颜色基本是 Element Plus 默认值。
- **间距写死**：页面里大量直接写 `gap: 12px`、`padding: 12px`、`margin-bottom: 16px` 等。
- **列表没有统一组件**：订单、我的商品页直接用 `el-table` 加 `el-pagination`。管理端统一的 `ListSearch`、`DataTable`、`CopyText`、`RowActions` 在达人端都没有。
- **外壳不同**：达人端是顶部导航条（`layout/index.vue` + `nav.ts`），管理端是页头 + 左侧菜单 + 内容区卡片。

## 2. 要做的事

### 2.1 令牌：两端完全一致

- 达人端新增 `kol-portal/src/style/tokens.css`，**内容与 `admin-ui/src/style/tokens.css` 逐字节一致**，在 `index.css` 中引入。去掉 `index.css` 里与之重复的定义，Element Plus 变量的映射方式与管理端相同。
- 扩展 `tokens.spec.ts`：
  - 断言两端 `tokens.css` 完全相同；
  - 扫描 `kol-portal/src`，禁止在 `.vue` 和 `.css` 中写死颜色（十六进制、`rgb()`）。间距也要改用令牌；确实需要写死的，列在测试的白名单里，并注明理由。

### 2.2 外壳：桌面端与管理端一致

- 页头：品牌（SparkTrack）、高度、底色、边框、右侧用户菜单与语言切换的样式，与管理端页头一致。达人端没有公司切换、消息铃铛、菜单搜索，这些不要加。
- 左侧菜单：沿用管理端 `SideMenu` 的样式（宽度、选中态、图标），菜单项来自现有的 `nav.ts`，并为每项配上 Element Plus 图标。达人端菜单少，**不需要**多标签页（TabBar）和折叠按钮。
- 内容区：页面底色与内容区内边距和管理端一致；不是列表的页面，内容超出时在内容区内滚动（与任务 53 相同的做法）。
- 登录、注册、找回密码、设置密码等认证页（`AuthShell`）：与管理端登录页使用同一套配色、卡片和按钮样式。

### 2.3 列表与卡片

- 把管理端的 `CopyText`、`ListSearch`、`DataTable`、`RowActions` 移植到 `kol-portal/src/components/`，接口和样式与管理端相同，连同它们依赖的工具函数（例如 `utils/table-prefs`）和语言包键（中英文）一并移植。只去掉达人端用不到、且依赖管理端特有模块的部分，并在文件头注释写明与管理端版本的差异。
- **订单**、**我的商品**改用 `ListSearch` 加 `DataTable`：搜索卡片 + 列表卡片，分页样式与管理端相同；可复制的编号用 `CopyText`。
- **首页**、**收款账户**、**账户**等非列表页，改用与管理端相同的卡片样式（`--tcrm-radius-card`、边框、内边距、标题字号）。
- 状态标签、空态、加载态、表单标签对齐方式，都与管理端相同。
- **手机端本期不考虑**：现有 ≤767px 的卡片布局保留即可，不要求重新设计，也不做手机验收；只要确认手机宽度下页面没有报错、能用。

### 2.4 规范更新

- **规范 10**：
  - 第 1 节写明两端使用同一份视觉令牌（颜色、圆角、阴影、间距、版式），达人端与管理端视觉一致（负责人 2026-10-24）；
  - 第 4 节第一条注明手机端设计在后续阶段处理。
- **规范 11 第 12 节**：达人端的列表同样使用 `ListSearch`、`DataTable`、`CopyText`、`RowActions`；修改两端共有的组件时，两端要同步修改。

## 3. 与并行 PR 的关系

PR #104（预付前端，产品方在改）会在达人端新增「收入—预付」页面和菜单项。本任务提交 PR 前先合并最新 `main`：#104 如果已合并，它新增的达人端页面也按本任务的样式改造；如果还没合并，在执行报告中注明，由架构负责人协调。

## 4. 测试

- 令牌契约测试（2.1）；
- 移植组件的单元测试：从管理端对应测试移植并调整；
- 订单、我的商品页的筛选参数与列；
- 外壳的菜单项与选中态；
- 中英文；
- 现有达人端测试全部通过，不得删减断言。

## 5. 浏览器验证

- 连接共享中间件，用已有的测试达人登录：Mila 等演示达人账号，密码以 seed 或负责人提供为准；不要修改他们的资料。
- 在 **1440×900、1280×800、2560×1440** 三种尺寸下截图：登录、注册、首页、我的商品、订单、收款账户、账户页，中英文都要。
- 与管理端对应页面并排比较配色、圆角、卡片和表格样式，在报告中逐项说明是否一致。
- 不修改任何业务数据。

## 6. 检查与交付

- kol-portal、admin-ui 的 lint、typecheck、test、build、notices:check 全部通过；检查、复核与浏览器验证交给子代理执行。
- 执行报告：逐条复核表、对照截图表（达人端与管理端）、移植组件的差异清单。提交 PR。不要合并、不要推 main、不要改任务索引。GitHub Actions 已暂停，不要等待 `gh pr checks`。
