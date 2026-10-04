# ADR-002 采用统一工程规范与 AGENTS.md

- 状态：已接受（2026-10-04）
- 决策人：项目负责人

## 背景

项目由一名工程师配合多个 AI 编码代理开发。第一轮骨架任务中，代码风格、分层、测试覆盖由代理自行决定，验证阶段发现 3 个缺陷，且代码结构与业界规范存在多处差距。

## 决定

1. 制定 `docs/05-工程规范/`，以阿里巴巴 Java 开发手册、Google Java Style、Spring Modulith 约定、Zalando API 指南、OWASP ASVS 5.0 第 2 级为依据。
2. 规范先于代码：现有代码不符合规范时整改代码。
3. 在仓库根目录放置 `AGENTS.md`（Linux 基金会下 AAIF 维护的开放标准），供所有 AI 代理启动时读取；`CLAUDE.md` 引用它。
4. 能自动检查的条款进入 CI（Spotless、Checkstyle、ArchUnit、JaCoCo、ESLint、类型检查），CI 不通过不能合并。
5. 改为分支 + PR 的交付流程，禁止直接推送 `main`。

## 影响

- 需要一次规范整改任务（任务 02）。
- 私有仓库在 GitHub 免费计划下无法设置分支保护，"禁止直接推送 main"依靠 AGENTS.md 约束与审查执行；如升级计划，应补充分支保护规则。
