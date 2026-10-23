# 任务 53：管理端内容区滚动修复（缺陷）

> **执行环境**：本机。工作树 `/Users/hh-mini/Public/dev/t-crm/.worktrees/53-page-scroll`，分支 `task/53-page-scroll`，已从最新 `main` 检出。管理端 Vite `8853`，联调用的后端端口 `TCRM_ADMIN_PORT=18531`。只改 `admin-ui/`，以及确有需要的规范文档。不新增迁移。
>
> **先完整阅读 [`本机任务通用要求.md`](本机任务通用要求.md)**，以及规范 10《前端视觉与布局规范》、规范 11《前端工程规范》。

## 1. 缺陷

负责人 2026-10-23 反馈：平台管理员的「系统配置」页内容超出屏幕后**没有滚动条**，下面的配置项看不到，也点不到。

原因：`layout/index.vue` 中 `.layout__main` 设置了 `overflow: hidden`，而 `.layout__watermark`（路由页面的容器）没有设置滚动。这样设计是为了让列表页由 `DataTable` 在表格内部滚动；但不是列表的页面就没有任何滚动容器，超出部分直接被截掉。这违反规范 10 第 48 行「内容区内滚动适应窄视口」。

路由页面中，既不用 `DataTable` 也没有自己滚动的有：

- `views/platform/system-config/index.vue`（系统配置）
- `views/system/post-template/index.vue`（岗位模板 / 权限设置）
- `views/account/password.vue`、`views/account/resignation.vue`
- `views/home/index.vue`（工作台，`layout/index.vue` 中对 `.home` 有单独处理）
- `views/fund/payout/index.vue`、`views/order/order-query/index.vue`、`views/product/shop-management/index.vue`（页签容器，要确认页签内的子页面）
- `views/common/ComingSoon.vue`、`views/error/403.vue`、`views/error/404.vue`

## 2. 要做的事

1. **先写复现测试**（规范 05「修复缺陷先写复现测试」）：在现有的布局或样式测试中加断言，确保路由页面的容器能在纵向滚动。
2. **在布局层统一修复，不要逐页打补丁**：让路由页面的容器（`.layout__watermark` 或它的直接子元素）在内容超出时出现纵向滚动条，页面本身不出现横向滚动。要求：
   - 列表页保持现状：`DataTable` 仍在表格内滚动，分页仍在视口内，不能出现内外两层滚动条；
   - 不是列表的页面，超出视口时在内容区内滚动，页头、侧边栏、页签栏保持固定；
   - 评估 `.layout__watermark :deep(> .home)` 的特殊处理是否还需要，不需要就删掉；
   - 浮层（弹窗、抽屉、下拉）的定位不受影响。
3. **逐页核对**第 1 节列出的页面，以及所有列表页抽查（达人列表、店铺管理、订单查询、商品任务、操作日志）。
4. **规范 10 第 7 节「验证」补一条**：不是列表的页面，要在内容超过一屏的状态下验证能滚动到底部。

## 3. 浏览器验证

连接共享中间件，按通用要求的矩阵截图，至少覆盖 1280×800、1440×900、375×812 三种尺寸：

- 平台管理员：系统配置，滚动到最后一项并打开编辑弹窗；
- 公司超管：岗位模板、工作台、申请离职页（如有入口）、达人列表、店铺管理、订单查询；
- 每张截图要能看出滚动位置：已经滚到底部，或者能看到滚动条。

不修改任何配置或业务数据；需要测试账号时用 `t53_` 前缀，收尾只删自己的数据。

## 4. 检查与交付

- admin-ui 的 lint、typecheck、test、build、notices:check 全部通过；检查与浏览器验证交给子代理执行。
- 执行报告含逐页复核表（页面、尺寸、修复前、修复后）；提交 PR。不要合并、不要推 main、不要改任务索引。GitHub Actions 已暂停，不要等待 `gh pr checks`。
