# 08 Git 与交付流程

## 1. 分支

【强制】`main` 是唯一长期分支，始终保持可构建、测试全部通过、可部署。
【强制】**禁止直接向 `main` 推送**；所有变更通过短期分支 + Pull Request 合并。
【强制】分支命名 `{类型}/{简短描述}`，类型同提交类型；对应开发任务时带任务编号：

| 示例 | 用途 |
|---|---|
| `task/02-engineering-baseline` | 执行 `docs/04-开发任务/` 中的任务 |
| `feat/order-attribution` | 新功能 |
| `fix/redis-blank-password` | 缺陷修复 |
| `docs/engineering-standards` | 文档 |

【强制】分支生命周期不超过一个任务；合并后删除。

## 2. 提交

【强制】提交信息遵循 Conventional Commits 1.0.0：

```
<type>(<scope>): <subject>

<body：为什么改、改了什么，可选>

<footer：关联任务、BREAKING CHANGE、署名>
```

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 不改变行为的重构 |
| `test` | 测试 |
| `docs` | 文档 |
| `chore` | 构建、依赖、配置 |
| `ci` | 持续集成 |
| `perf` | 性能 |

【强制】`scope` 使用工程或模块名：`backend`、`redirect`、`admin-ui`、`kol-portal`、`deploy`，或后台模块名 `order`、`commission` 等。
【强制】一个提交只做一件事，且提交后仍可构建；不把格式化、重构与功能修改混在同一个提交。
【强制】AI 代理产生的提交在末尾署名：Codex 为 `Co-Authored-By: Codex <noreply@openai.com>`，Claude 为 `Co-Authored-By: Claude <noreply@anthropic.com>`（以所用模型实际署名为准）。

## 3. Pull Request

【强制】PR 标题同提交格式；描述使用仓库的 PR 模板，包含：变更内容、关联任务、验证方式与结果、新增依赖说明、数据库变更说明、截图（涉及界面时）。
【强制】合并条件：
1. CI 全部通过（见第 4 节）；
2. 对照本规范完成审查（AI 代理提交的 PR 由项目负责人或 Claude 审查）；
3. 无未解决的审查意见。
【强制】合并方式使用 **Squash merge**，合并后的提交信息符合第 2 节格式。
【推荐】单个 PR 的改动不超过 800 行（不含锁文件、迁移脚本、自动生成文件）；超过时拆分。

## 4. 持续集成（GitHub Actions）

【强制】每个 PR 与每次合并到 `main` 时运行：

| 工程 | 检查项 |
|---|---|
| backend | Spotless 格式校验、Checkstyle、编译、单元测试、ArchUnit、集成测试（`-Pdocker-it`，GitHub 托管机器自带 Docker）、JaCoCo 覆盖率门槛、依赖漏洞扫描 |
| redirect | 同上（无 ArchUnit 模块规则时只检查分层） |
| admin-ui / kol-portal | `pnpm install --frozen-lockfile`、ESLint、Prettier 校验、类型检查、Vitest、构建、`pnpm audit` |
| deploy | `docker compose config` 语法校验 |

【强制】CI 失败的 PR 不得合并；不得为了通过而关闭检查或降低门槛（调整门槛须修改本规范）。

## 5. 完成标准（Definition of Done）

一个任务或 PR 只有同时满足以下条件才算完成：

- [ ] 功能符合设计说明书与任务要求，未超出范围
- [ ] 符合本规范；自动检查全部通过
- [ ] 必测清单中相关项已有测试，覆盖率达标
- [ ] 数据库变更有迁移脚本，接口变更有 OpenAPI 描述，前端错误码映射已更新
- [ ] 设计说明书、README、`.env.example` 等受影响文档已同步
- [ ] 无遗留调试代码、无未登记的 `TODO`
- [ ] 已通过 PR 合并到 `main`

## 6. 开发任务与执行报告

【强制】交给 AI 代理的开发任务写在 `docs/04-开发任务/{编号}-{名称}-Codex提示词.md`，代理完成后在同目录写 `{编号}-{名称}-执行报告.md`。
【强制】执行报告至少包含：结论、版本与环境、各步骤的验证命令与结果、偏离与原因、遗留事项、PR 链接。
【强制】任务完成的判定以 CI 结果与审查结论为准，不以执行报告的自述为准。

## 7. 架构决策记录（ADR）

【强制】以下决定须在 `docs/06-架构决策/ADR-{序号}-{标题}.md` 记录：技术选型、主版本升级、规范修改、偏离规范的例外、影响多个模块的设计取舍。
【强制】格式：背景、决定、备选方案及放弃原因、影响。已接受的 ADR 不修改，被推翻时新增 ADR 并标注替代关系。

## 8. 版本与发布

【推荐】遵循语义化版本：上线首个版本为 `1.0.0`；发布时在 `main` 打标签 `v{版本}`，根据提交信息生成更新说明。
