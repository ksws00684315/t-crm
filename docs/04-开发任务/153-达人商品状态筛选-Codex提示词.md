# 任务 153：达人商品列表按状态筛选

负责人 2026-11-13 在对话中要求：管理端「达人商品」的查询增加一个状态条件。152 已被分支 `task/152-copy-source-from-copy` 占用，本任务使用 153。进度表 06.15 的筛选项没有状态，本条登记为偏离清单 D-30。

## 1. 执行环境

- 工作树：`/Users/hh-mini/Public/dev/t-crm/.worktrees/kol-item-status-filter`（目录名在编号确定前已建，不改目录，避免挪动正在使用的工作树）
- 分支：`task/153-kol-item-status`，基于 `origin/main`（`002b5fbc`）
- 只改本工作树。不改主目录和其他工作树
- 无迁移，不连共享中间件。集成测试用 Testcontainers
- 不占负责人端口。不另起预览

## 2. 需求

对照进度表「功能评估-P0」第 32 行（06.15）。原文筛选是链接编码、达人（名称 / ID / 邮箱）、目标店铺。状态和清理状态是列表展示列。

负责人本次确认的增量（D-30）：

- 查询增加状态，取值与列表列一致：启用 `ENABLED`、停用 `DISABLED`
- 不传或空白时仍同时返回启用和停用
- 不增加清理状态筛选，不按原商品上下架筛选，不改达人端「我的商品」

状态不是 `crm_kol_item` 的列。启用条件与 `KolItemStatus.mapping` 相同：`valid_from <= 当前时间` 且（`valid_to` 为空或 `valid_to > 当前时间`）。`valid_to` 等于当前时间算停用。`valid_from` 列在库里是非空，查询仍保留空值分支，与内存计算一致。

## 3. 范围

改：

- `GET /admin-api/product/kol-items` 的查询参数、服务分页条件
- 管理端达人商品页的筛选、筛选条、导出（导出走同一组已应用条件）
- 设计说明书中该列表的参数和状态说明
- 任务索引、偏离清单 D-30、本提示词和执行报告

不做：

- 清理状态筛选、原商品上下架筛选、达人端
- 新列、新索引、新迁移、新错误码、新语言包词条
- 改任务 39 的历史提示词

## 4. 参照

- 查询入参：`KolItemQuery`、`KolItemFilterDTO`，空白文本用已有的 `blankToNull`
- 状态规则：`KolItemStatus.mapping`
- 页面筛选：`admin-ui/src/views/product/kol-item/index.vue` 里目标店铺下拉（可清空、占位「全部」、`ListSearch`）
- 文案：已有 `mapping.status`、`mapping.statuses.ENABLED`、`mapping.statuses.DISABLED`

## 5. 检查

后端 JDK 21：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。至少跑 `KolItemServiceTest` 和 `KolItemQueryIT`（`-Pdocker-it`）。管理端至少跑 `src/views/product/kol-item/__tests__/kol-item-page.spec.ts`。

界面改动按规范 12 第 5 节做浏览器验收。本提示词不把未跑的全量检查写成已通过。
