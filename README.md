# 海外达人分销 CRM

自研模块化单体，面向海外达人分销业务。交付范围以 `docs/01-报价与合同/` 的签约定稿为准，设计以 `docs/03-设计/系统详细设计说明书.md` 为准。当前后台包含管理员登录、用户管理、操作审计；独立跳转服务提供 302 跳转与 Redis 点击事件写入。其他业务模块目前仅占位。

任务 02 当前为部分完成：Java 构建及 Docker 集成 CI 已通过，但 Checkstyle 引擎的 LGPL 许可与规范 07 白名单冲突，OSV 还有 ArchUnit 许可元数据告警。按任务停止条件保留 [Draft PR #2](https://github.com/ksws00684315/t-crm/pull/2)，详情见 [执行报告第 10 节](docs/04-开发任务/02-工程化基线与规范整改-执行报告.md#10-第三次续做记录2026-10-05)。

## 目录

| 目录 | 内容 |
|---|---|
| `backend/` | 后台 `crm-admin`，默认端口 8080 |
| `redirect/` | 独立跳转服务 `crm-redirect`，默认端口 8081 |
| `config/` | 两工程共用的 Checkstyle 规则及第三方许可证原文、声明 |
| `scripts/` | 第三方通知生成脚本，仅使用 Python 标准库 |
| `.github/` | Java / Docker / OSV 门禁、Dependabot、PR 模板 |
| `docs/` | 合同、设计、开发任务、工程规范、ADR |
| `admin-ui/`、`kol-portal/` | 待任务 03 补充 |
| `deploy/` | 待任务 03 补充 |

## 版本

| 项目 | 当前固定版本 |
|---|---|
| 两工程 | 0.1.0；Java 21；Spring Boot 4.1.1 |
| Sa-Token / MyBatis-Plus / Redisson | 1.46.0 / 3.5.17 / 4.8.0（后台） |
| PostgreSQL / JDBC / Flyway | PostgreSQL 17；JDBC 42.7.13；Flyway 12.4.0（后台） |
| Tomcat / Jackson BOM | 11.0.26 / 3.1.7（按安全修复例外覆盖） |
| Spotless / google-java-format | 3.10.3 / 1.28.0 |
| Maven Checkstyle / Checkstyle 引擎 | 3.6.0 / 14.3.0（引擎许可证待负责人裁定） |
| JaCoCo / ArchUnit | 0.8.15 / 1.5.1 |
| license-maven-plugin / Surefire / Failsafe | 2.7.1 / 3.5.5 / 3.5.5 |
| Testcontainers / 测试镜像 | 2.0.5；`postgres:17.11`、`redis:7.4.5` |

实际依赖以各工程 `pom.xml` 为准；新增版本、原文及兼容依据见执行报告。Redis 运行环境采用 7.x。

## 本地开发

安装 JDK 21、Maven、Python 3；后台运行需要 PostgreSQL 17 和 Redis，跳转服务只需 Redis。复制 `.env.example` 到 `.env`，填写本机账号、密码、首次超管凭证以及跳转回退地址和 IP 哈希盐。不要提交 `.env`。Spring Boot 不会自动读取该文件，应通过 IDE 或终端把其中的值设为环境变量。首次超管凭证无默认值；已有超管时不会重复创建。

在所选工程目录运行：

```bash
mvn spotless:apply
mvn verify
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`verify` 校验格式、导入及输出规则、单元测试、ArchUnit、覆盖率。后台整体行/分支门槛为 70% / 60%，security 及未来 commission、order、stats 为 85% / 80%；跳转为 80% / 70%。`dev` 使用同一环境变量配置；当前没有引入 springdoc，原因见报告，不提供 OpenAPI 页面。

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

运行时通知包含完整依赖列表、许可证原文、版权与致谢，随 JAR 放入 `META-INF/THIRD-PARTY-NOTICES`；CI 重生成后比较已提交文件，不一致即失败。原文来源见 [许可说明](config/licenses/README.md)。镜像交付待任务 03 补充。

## 接口与规范

后台管理员令牌放在 `X-Admin-Token` 请求头。认证接口前缀 `/admin-api/auth`；用户管理为 `/admin-api/system/users`，审计列表为 `/admin-api/system/oper-logs`，后两者要求 `SUPER_ADMIN`。后台信封为 `{code,msg,data,errorCode,traceId}`，分页为 `{items,total,page,size}`；64 位整数与金额输出字符串，时间采用 UTC `Instant`。每个请求返回 `X-Trace-Id`。跨域来源仅允许 `TCRM_CORS_ALLOWED_ORIGINS` 的逗号分隔白名单，默认关闭。

跳转服务继续返回 302 与纯文本健康检查，不使用后台信封。其链接缓存初始化仍是任务 01 登记的 TODO，后续业务任务负责。

编码前读取 [AGENTS.md](AGENTS.md)、[工程规范](docs/05-工程规范/README.md) 和 [ADR](docs/06-架构决策/)。使用任务分支及 Conventional Commits；禁止直接推送 main。由负责人审查并合并 PR。
