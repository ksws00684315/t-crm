# AGENTS.md

本文件是给 AI 编码代理（Codex、Claude Code 等）的项目规则摘要。**动手前必须读完本文件**；细则以 `docs/05-工程规范/` 为准，两者冲突时以工程规范为准。

本文件只写**项目规则**，所有协作者及其 AI 工具共同遵守。个人偏好不要写进本文件，放在各自不提交的位置：Claude Code 用用户级 `~/.claude/CLAUDE.md` 或项目内 `CLAUDE.local.md`（已在 `.gitignore` 中）；Codex 用用户级 `~/.codex/AGENTS.md`。个人偏好与本文件冲突时，以本文件为准。

## 项目

海外达人分销 CRM。单人 + AI 开发，交付给客户。范围以 `docs/01-报价与合同/` 的签约定稿为准，设计以 `docs/03-设计/系统详细设计说明书.md` 为准。

| 工程 | 技术 | 说明 |
|---|---|---|
| `backend/` | Java 21、Spring Boot 4.1、Sa-Token、MyBatis-Plus、Flyway、Redisson、PostgreSQL 17 | 后台 crm-admin，模块化单体 |
| `redirect/` | Java 21、Spring Boot 4.1、Redis | 跳转服务，独立进程，保持极简 |
| `admin-ui/` | Vue 3、TypeScript、Element Plus（基于 pure-admin-thin） | 管理端，中文 |
| `kol-portal/` | Vue 3、TypeScript、Element Plus | 达人端，英文 |
| `deploy/` | Docker Compose、Nginx | 部署模板 |

## 常用命令

```bash
# 后台 / 跳转服务（JDK 21）
mvn spotless:apply                # 格式化
mvn verify                        # 格式、静态检查、单元测试、架构测试、覆盖率
mvn verify -Pdocker-it            # 集成测试（需要 Docker）

# 前端
pnpm install --frozen-lockfile
pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

## 必须遵守

1. **范围**：只做任务要求的内容。不加未要求的功能、抽象、配置项、依赖。发现范围外的问题，写进报告，不顺手改。
2. **架构**（规范 01）：后台根包 `com.tcrm.admin` 的直接子包是模块；模块内分 `controller/service/mapper/model/api`；调用方向 controller → service → mapper；模块之间只能依赖对方的 `api` 包；`*DO` 不出现在接口入参出参。
3. **编码**（规范 02、10、11、13）：Google Java Style（Spotless）；禁止通配符导入、`public` 实例字段、Lombok、字段注入、`System.out`；金额用 `BigDecimal`，时间用 `Instant`（UTC）。前端界面文字从语言包读取（中英文），不写死；`formatInstant` 跟当前语言，名称+编码走 `common.nameWithCode` / 工具函数；Controller 不加类级 `@Validated`（规范 03）；现行全站字体、间距及响应式布局遵守规范 10；规范 13《前端视觉与交互规范（曼曼版）》第 2 章文字与颜色、第 3.1～3.4 节列表页、面包屑与详情抽屉的已选布局及附录 A 表格方案 A 已确认，整份规范定稿前不据此批量修改页面；管理端列表必须用 `ListSearch`、`DataTable`、`RowActions`、`CopyText`，看板图表只用 ECharts 并经 `ChartPanel` 等看板公共组件，未实现的功能显示「开发中」（规范 11）。
4. **接口**（规范 03）：路径复数 kebab-case；统一信封 `{code,msg,data,errorCode,traceId}`；错误码登记在 `shared.ErrorCode`；分页 `{items,total,page,size}`；选择器全量列表用专用 `List` 接口，禁止 `size=100` 冒充全量；ID 与金额序列化为字符串。
5. **数据库**（规范 04）：只通过 Flyway 迁移，脚本名 `V{yyyyMMddHHmm}__描述.sql`；**已合并的脚本不得修改**；表和字段必须有注释；只用参数绑定；业务表必须有 `tenant_id`（所属公司）。
6. **测试**（规范 05）：业务规则必须有单元测试；集成测试命名 `*IT` 并标注 `@Tag("docker")`；修复缺陷先写复现测试；不得跳过或弱化失败的测试；覆盖率门槛不得降低。
7. **安全**（规范 06）：不提交任何密钥和真实账号；不写默认密码；每个接口声明所需角色或权限码（ADR-018）；达人 ID 与公司 ID 只从登录态取，跨公司处理必须显式声明；日志不输出个人信息与凭证。
8. **依赖**（规范 07）：直接依赖只用正式稳定版并写死版本；传递依赖中的预发布版本只记录不阻断；新增依赖须说明理由；禁止 GPL/AGPL 许可证。
9. **Git**（规范 08）：**禁止直接推送 `main`**（唯一例外是项目负责人明确指示的纯过程文档提交，见规范 08 第 1 节）；在 `task/{编号}-{名称}` 等分支上开发，提交遵循 Conventional Commits，完成后推送分支并创建 PR；禁止 `push --force`、`reset --hard`、改写已推送历史。
10. **需求与验收**（规范 12）：功能以进度跟踪表「功能说明」原文为准，截图只作布局参考；客户补充资料须登记进提示词后才实现，不在其他任务中顺带做；执行报告必须有逐条复核表；涉及界面的做浏览器验收，涉及配置或部署的用 `preview.sh up` 实际启动。
11. **本地环境**（规范 08 第 9 节）：只在分配给你的工作树中读写文件，子代理输入写明工作目录，**不得写主目录**；联调连共享中间件（`deploy/.env.local` 只读），测试数据加 `tNN_` 前缀，不得停止或删除共享中间件，不得 `down -v`。
12. **完成标准**：本地能运行的检查全部通过、文档同步、写执行报告；最终以 CI（暂停期间为审查人的本地复跑）和审查结论为准。

## 停下来报告，而不是自行决定

- 需求、设计、规范之间存在冲突或歧义；
- 需要新增规范之外的依赖、模块，或修改已合并的迁移脚本；
- 需要降低测试或覆盖率门槛才能通过；
- 验证在合理排查后仍失败。
