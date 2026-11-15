# 任务 154：提现基础配置保存修复

负责人 2026-11-15 在 PR #280 审查中要求把审查发现的问题在本会话内一并修掉，并按仓库现有最大任务号顺延使用 154。PR #280 的分支已在远端，本任务沿用该分支，不重建 PR。

## 1. 执行环境

- 工作树：`/Users/hh-mini/Public/dev/t-crm/.worktrees/280-settlement-config-save`
- 分支：`task/fix-settlement-config-save`，基于 `origin/main`（`8a8a37ea`），对应现有 PR #280
- 只改本工作树。不改主目录和其他工作树
- 无迁移。浏览器验收连共享中间件（`deploy/.env.local` 只读）
- 后端 18854，Vite 18855，不占负责人端口

## 2. 背景与缺陷

管理端「财务结算 > 结算配置」保存失败。设计说明书 §6.2 规定 `PUT /admin-api/commission/settlement-configs/current` 的请求体为 `{version, minimum, days}`，`version` 为数字乐观锁，`days` 为 0～365 的整数。原前端把读取到的完整响应对象（含 `currency`、`channels`）直接 PUT，缺少顶层 `days`，后端 `SaveRequest.days` 的 `@NotNull` 校验失败，返回 400。

同一保存路径还有一个残留问题：冻结天数输入框清空后，页面把 `days: null` 发给后端，仍会 400，且用户看不到具体原因。

## 3. 范围

改：

- `admin-ui/src/api/commission/finance.ts`：保存时只提交 `version`、`minimum` 和 `WEIDIAN` 渠道的 `days`
- `admin-ui/src/api/commission/finance-types.ts`：`SettlementConfig.version` 收窄为 `number`
- `admin-ui/src/views/fund/settlement-config/index.vue`：保存前校验冻结天数为 0～365 的整数，清空时给出中英文提示
- 中英文语言包、预览种子与预览保存的版本号、旧预览状态的版本迁移
- 契约测试、页面测试、预览引擎测试
- 本提示词、执行报告、任务索引、验收截图

不做：

- 后端、数据库、迁移、依赖
- 结算配置的其他字段或交互
- 预览以外的演示数据

## 4. 验收

- 正常保存：改最低提现金额和冻结天数，保存成功，版本号递增，请求只含 `version`、`minimum`、`days`
- 清空冻结天数：保存被前端拦截，中文提示「请输入 0 到 365 之间的冻结天数」，英文提示「Enter freeze days between 0 and 365」，不发请求
- 浏览器验收按规范 12 第 5 节，1440×900、1280×800、2560 宽，中英文
- 收尾恢复共享库结算配置为默认值 200.00 / 7 天
