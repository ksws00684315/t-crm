# 海外达人分销 CRM（SparkTrack）

自研模块化单体，面向海外达人分销业务。交付范围以 `docs/01-报价与合同/` 的签约定稿为准，设计以 `docs/03-设计/系统详细设计说明书.md` 为准，功能口径以进度跟踪表「功能说明」原文和 [进度表基线偏离清单](docs/04-开发任务/进度表基线偏离清单.md) 为准。

**现状**（2026-11-10 按全量审查 M-23 重写，任务 145）：正式环境部署在 AWS（ADR-037），自 `v0.1.0`（2026-11-05）起按发布标签上线。各任务的进度见 [开发任务索引](docs/04-开发任务/README.md)，未处理的问题见 [遗留问题清单](docs/04-开发任务/遗留问题清单.md)。

## 工程

| 目录 | 内容 |
|---|---|
| `backend/` | 后台 `crm-admin`（Java 21、Spring Boot 4.1、Sa-Token、MyBatis-Plus、Flyway、Redisson、PostgreSQL 17），默认端口 8080 |
| `redirect/` | 独立跳转服务 `crm-redirect`（Java 21、Spring Boot 4.1、Redis），只读 Redis、不回源后台（ADR-012），默认端口 8081 |
| `admin-ui/` | 管理端（Vue 3、TypeScript、Element Plus，基于 pure-admin-thin），中文 |
| `kol-portal/` | 达人端（Vue 3、TypeScript、Element Plus），英文 |
| `deploy/` | Docker Compose、Nginx、备份、本地预览与发布脚本，见 [deploy/README.md](deploy/README.md) 与 [RUNBOOK](deploy/RUNBOOK.md) |
| `config/`、`scripts/` | 共用 Checkstyle 规则、第三方许可原文与通知生成脚本 |
| `.github/` | CI 工作流、发布工作流、Dependabot、PR 模板 |
| `docs/` | 需求、合同、设计、开发任务、工程规范、ADR |

后台模块（`com.tcrm.admin` 的直接子包，规范 01）：`security`（登录、账号、权限）、`system`（公司、用户、系统配置、操作日志）、`kol`（达人）、`kollibrary`（TikTok 达人库，ADR-038）、`product`（店铺、商品、对应关系、推广表格、自动复制）、`link`（中转链接与点击）、`order`（订单与退款同步、结算流水）、`commission`（计佣、钱包、提现、预付）、`notification`（站内通知）、`integration`（微店接口）、`task`（导入导出任务）、`shared`（公共基础设施）。`stats`、`sheet` 目前只有包声明（占位；独立经营分析看板暂缓）。

## 版本

镜像与交付包的版本只以 git 发布标签 `v{版本}` 为准；各工程 `pom.xml`、`package.json` 中的版本号是开发版本，发布时不改（规范 08 §8）。依赖版本以各工程 `pom.xml`、`pnpm-lock.yaml` 为准。

## 本地开发

本机开发与 AI 代理联调共用一套 PostgreSQL / Redis，启动方式、连接配置和测试数据规则见 [deploy/README.md](deploy/README.md)「本地共享中间件与开发」；整套应用的本地预览用 `deploy/scripts/preview.sh`（同文件「本地预览」）。

常用检查命令（只跑改动涉及的工程，见规范 08）：

```bash
# backend/ 或 redirect/（JDK 21）
mvn spotless:apply
mvn verify                 # 格式、静态检查、单元测试、ArchUnit、覆盖率
mvn verify -Pdocker-it     # 集成测试（*IT，@Tag("docker")，需要 Docker）

# admin-ui/ 或 kol-portal/
pnpm install --frozen-lockfile
pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

依赖变化后按 [许可说明](config/licenses/README.md) 重新生成并提交各工程的 `THIRD-PARTY-NOTICES`。

## 接口约定

后台信封为 `{code,msg,data,errorCode,traceId}`，分页为 `{items,total,page,size}`，64 位整数与金额输出字符串，时间为 UTC `Instant`；接口清单在设计说明书中维护，不提供 OpenAPI 页面（ADR-040）。细则见规范 03。部署在代理之后时必须配置 `TCRM_TRUSTED_PROXIES`（ADR-009）。

## 早期遗留（2026-10-05 审查）

以下问题已在 2026-10-05 审查中确认，暂不处理，留给后续业务任务：

- 用户、达人、商品的修改接口没有乐观锁：每个操作只写自己负责的列，不会再把旧的状态或密码写回，但两人同时修改同一字段时，后写覆盖先写。
- 管理端允许同一账号多端同时登录，每次登录签发新令牌（Sa-Token 1.46 默认 `is-concurrent=true`、`is-share=false`），每个账号最多 12 个令牌；会话策略见设计说明书 §3.3（负责人已接受）。
- 操作审计由 `@Async` 异步写入，写入失败或进程退出时可能丢失少量记录（内部系统，负责人已接受）。

编码前读取 [AGENTS.md](AGENTS.md)、[工程规范](docs/05-工程规范/README.md) 和 [ADR](docs/06-架构决策/)。在任务分支上开发并使用 Conventional Commits；禁止直接推送 `main`，由负责人审查并合并 PR。
