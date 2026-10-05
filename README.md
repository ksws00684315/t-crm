# 海外达人分销 CRM

自研模块化单体，面向海外达人分销业务。交付范围以 `docs/01-报价与合同/` 的签约定稿为准，设计以 `docs/03-设计/系统详细设计说明书.md` 为准。当前后台包含管理员登录、用户管理、操作审计；独立跳转服务提供 302 跳转与 Redis 点击事件写入；管理端 `admin-ui` 提供登录、用户管理、操作日志、修改密码；达人端 `kol-portal` 仅有骨架（登录页占位）；`deploy/` 提供部署模板。其他业务模块目前仅占位。

任务 02（工程化基线与规范整改）已通过 [PR #2](https://github.com/ksws00684315/t-crm/pull/2) 合并；Checkstyle 按 ADR-007 保留，ArchUnit 完整组合许可已声明。执行记录见 [执行报告](docs/04-开发任务/02-工程化基线与规范整改-执行报告.md)。任务 03（前端与部署）待执行。

## 目录

| 目录 | 内容 |
|---|---|
| `backend/` | 后台 `crm-admin`，默认端口 8080 |
| `redirect/` | 独立跳转服务 `crm-redirect`，默认端口 8081 |
| `config/` | 两工程共用的 Checkstyle 规则及第三方许可证原文、声明 |
| `scripts/` | 第三方通知生成脚本，仅使用 Python 标准库 |
| `.github/` | Java / Docker / OSV 门禁，管理端、达人端、部署模板工作流，Dependabot、PR 模板 |
| `docs/` | 合同、设计、开发任务、工程规范、ADR |
| `admin-ui/` | 管理端（Vue 3 + TypeScript + Element Plus），源自 pure-admin-thin v6.2.0 的工具链与约定，见 `admin-ui/README.md` |
| `kol-portal/` | 达人端骨架（英文界面，手机宽度适配），登录接口待后续任务 |
| `deploy/` | 环境变量示例、开发依赖与生产 compose、Nginx、备份脚本，见 `deploy/README.md`；两个 Dockerfile 在 `backend/`、`redirect/` |

## 版本

| 项目 | 当前版本 |
|---|---|
| 两工程 | 0.1.0；Java 21；Spring Boot 4.1.1 |
| Sa-Token / MyBatis-Plus / Redisson | 1.46.0 / 3.5.17 / 4.8.0（后台） |
| PostgreSQL / JDBC / Flyway | PostgreSQL 17；JDBC、Flyway 由 Spring Boot 管理（Boot 4.1.1 解析为 42.7.13 / 12.4.0，后台） |
| Tomcat / Jackson BOM | 11.0.26 / 3.1.7（按安全修复例外覆盖） |
| Spotless / google-java-format | 3.10.3 / 1.28.0 |
| Maven Checkstyle / Checkstyle 引擎 | 3.6.0 / 14.3.0（构建工具，见 ADR-007） |
| JaCoCo / ArchUnit | 0.8.15 / 1.5.1 |
| license-maven-plugin / Surefire / Failsafe | 2.7.1；后两者由 Spring Boot 管理（Boot 4.1.1 解析为 3.5.6） |
| Testcontainers / 测试镜像 | 2.0.5；`postgres:17.11`、`redis:7.4.5` |
| 前端工具链 | Node.js 24 LTS（`^22.13 \|\| >=24`）；pnpm 10.33.4；Vite 7.3.6；Vue 3.5.43；Element Plus 2.14.7；Vitest 5.0.3；TypeScript 5.9.3 |
| 部署镜像 | `nginx:1.30.5-alpine`；运行镜像 `eclipse-temurin:21.0.12.1_1-jre` |

pom 中未写 `<version>` 的依赖与插件跟随 Spring Boot 升级（见 `.github/dependabot.yml` 的策略说明），其余版本固定写在 `pom.xml`；实际版本以各工程 `pom.xml` 与 `mvn dependency:tree` 为准；新增版本、原文及兼容依据见执行报告。Redis 运行环境采用 7.x。

## 本地启动顺序

1. 数据库：`cp deploy/.env.example deploy/.env` 并填写，然后 `docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d`（PostgreSQL 17、Redis 7）。宿主机 5432 / 6379 已被占用时，修改 `deploy/.env` 的 `TCRM_DB_PORT`、`TCRM_REDIS_PORT`，后台与跳转服务读取同名变量。
2. 后台：导入 `deploy/.env` 的变量后在 `backend/` 运行 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`（默认 8080；Flyway 自动建表）。
3. 跳转服务：同样导入变量后在 `redirect/` 运行（默认 8081）。
4. 管理端：`cd admin-ui && pnpm install --frozen-lockfile && pnpm dev`，`/admin-api` 代理到 `http://localhost:8080`（`VITE_ADMIN_API_TARGET` 可改）。
5. 达人端：`cd kol-portal && pnpm install --frozen-lockfile && pnpm dev`。

前端校验命令（两个前端相同）：`pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm build`；依赖变化后运行 `pnpm notices` 重新生成 `THIRD-PARTY-NOTICES`，`pnpm notices:check` 校验与已提交文件一致。逐项验证见 [本机验证清单](docs/04-开发任务/01-项目骨架搭建-本机验证清单.md)。

## 本地开发（后端工程）

安装 JDK 21、Maven、Python 3；后台运行需要 PostgreSQL 17 和 Redis，跳转服务只需 Redis。复制 `deploy/.env.example` 到 `deploy/.env`，填写本机账号、密码、首次超管凭证以及跳转回退地址和 IP 哈希盐；在反向代理（Nginx / Cloudflare）之后部署时还必须设置 `TCRM_TRUSTED_PROXIES`（见下文）。不要提交 `.env`。Spring Boot 不会自动读取该文件，应通过 IDE 或终端把其中的值设为环境变量。首次超管凭证无默认值；不存在启用且未删除的超级管理员时，两个初始化变量均必须设置，缺少任意一个会启动失败并提示变量名。已有可用超管时无需初始化变量，也不会重复创建。

在所选工程目录运行：

```bash
mvn spotless:apply
mvn verify
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`verify` 校验格式、导入、输出、大括号、单行语句、变量声明、空 catch、switch 分支等规则，以及单元测试、ArchUnit、覆盖率。后台整体行/分支门槛为 70% / 60%，security 及未来 commission、order、stats 为 85% / 80%；跳转为 80% / 70%。`dev` 使用同一环境变量配置；当前没有引入 springdoc，原因见报告，不提供 OpenAPI 页面。

具备 Docker 的机器运行：

```bash
mvn verify -Pdocker-it
```

Docker 用例为 `*IT`、`@Tag("docker")`，普通 `verify` 编译但不执行它们。任务执行机未运行 Docker；GitHub Actions 实际运行 PostgreSQL / Redis 集成测试，并上传测试与 JaCoCo 报告。

依赖变更后，在每个工程目录重新生成许可文件（需要 Maven Central 网络）：

```bash
mvn generate-resources dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
python3 ../scripts/generate-notices.py .
git diff -- THIRD-PARTY-NOTICES
```

运行时通知包含完整依赖列表和来源、共享原文索引与文本块；通过编号在文件内还原完整许可证、版权与致谢，相同内容只存一份。通知随 JAR 放入 `META-INF/THIRD-PARTY-NOTICES`；CI 先验证 5 项离线去重回归用例，重生成时逐份核对原文可还原性，再比较已提交文件，不一致即失败。原文来源见 [许可说明](config/licenses/README.md)。镜像：`backend/Dockerfile`、`redirect/Dockerfile` 以仓库根目录为构建上下文，镜像内 `/licenses/` 含 backend、redirect、admin-ui、kol-portal 四份第三方许可声明；静态资源由 `deploy/scripts/build-static.sh` 生成同样的声明。部署说明见 [deploy/README.md](deploy/README.md)。

## 接口与规范

后台管理员令牌放在 `X-Admin-Token` 请求头。认证接口前缀 `/admin-api/auth`；用户管理为 `/admin-api/system/users`，审计列表为 `/admin-api/system/oper-logs`，后两者要求 `SUPER_ADMIN`。后台信封为 `{code,msg,data,errorCode,traceId}`，分页为 `{items,total,page,size}`；64 位整数与金额输出字符串，时间采用 UTC `Instant`。每个请求返回 `X-Trace-Id`。跨域来源仅允许 `TCRM_CORS_ALLOWED_ORIGINS` 的逗号分隔白名单，默认关闭。列表接口 `page` 最大 10000、`size` 最大 100。

**客户端 IP 与登录限流**：只有当 TCP 对端属于 `TCRM_TRUSTED_PROXIES`（逗号分隔的 IP/CIDR，默认空 = 不信任任何代理）时，才会读取 `CF-Connecting-IP`、`X-Forwarded-For`（可用 `TCRM_CLIENT_IP_HEADERS` 调整），详见 [ADR-009](docs/06-架构决策/ADR-009-可信代理与客户端IP解析.md)。登录失败在 15 分钟窗口内按“用户名+IP 5 次 / IP 20 次 / 用户名全部 IP 50 次”三层锁定 15 分钟（429），详见 [ADR-008](docs/06-架构决策/ADR-008-登录失败限流按用户名与来源IP分层.md)。未配置可信代理而部署在代理之后时，所有请求会被视为同一个 IP，20 次失败即全站锁定，生产环境必须配置。

跳转服务继续返回 302 与纯文本健康检查，不使用后台信封。其链接缓存初始化仍是任务 01 登记的 TODO，后续业务任务负责。链接索引值只接受 JSON（见设计说明书 §7.4）；点击流 `stream:click` 以近似 `MAXLEN` 写入，上限 `TCRM_CLICK_STREAM_MAXLEN`（默认 1000000）。

## 遗留清单

以下问题已在 2026-10-05 审查中确认，暂不处理，留给后续业务任务：

- 用户列表按用户名模糊查询（`ILIKE`）时未转义通配符 `%`、`_`，关键字含这两个字符会匹配过宽。
- 用户更新接口没有乐观锁，并发修改时后写覆盖先写。
- Sa-Token 沿用默认的并发登录策略，未配置空闲超时，只有 8 小时绝对超时（`sa-token.timeout`）。
- 操作审计由 `@Async` 异步写入，写入失败或进程退出时可能丢失少量记录（内部系统，负责人已接受）。

编码前读取 [AGENTS.md](AGENTS.md)、[工程规范](docs/05-工程规范/README.md) 和 [ADR](docs/06-架构决策/)。使用任务分支及 Conventional Commits；禁止直接推送 main。由负责人审查并合并 PR。
