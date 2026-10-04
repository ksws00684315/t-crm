# 任务：搭建海外达人分销 CRM 项目骨架

> 本文件是交给编码代理（Codex）执行的提示词。直接把本文件全文作为任务输入即可。

---

## 1. 背景

你在仓库 `t-crm`（git 仓库，当前分支 `main`，已有一次提交，只含 `docs/`）中工作。这是一个海外达人分销 CRM 系统，由一名工程师配合 AI 开发。开始前**先通读**：

- `docs/03-设计/系统详细设计说明书.md`：重点读 §0.2（技术决策）、§2（总体架构）、§4.5（点击跳转）、§7.4（Redis 链接索引）、§10（非功能）、§12（部署与配置）
- `docs/README.md`：文档索引

**执行环境说明**：执行本任务的机器资源有限，**不能运行 Docker**（也不要尝试安装或启动 Docker）。凡是需要 MySQL、Redis 容器或 Testcontainers 的运行验证，都不在本机执行，而是推送后由另一台机器按 `docs/04-开发任务/01-项目骨架搭建-本机验证清单.md` 验证。因此你要保证：不依赖 Docker 的验证（编译、单元测试、前端构建、静态检查）全部在本机通过；依赖 Docker 的验证代码和配置写好、可以直接运行。

本任务只搭**可编译、可测试的工程骨架**，不实现业务功能（达人、商品、订单、佣金、看板等都不做），唯一例外是第 6 步的跳转服务最小实现。

## 2. 已确定的技术决策（不要更改）

| 编号 | 决策 |
|---|---|
| D1 | 后端脚手架使用 **RuoYi-Vue-Plus 6.0.x 的最新稳定版**（Spring Boot 4.1、JDK 21）。必须是正式发布的稳定 tag，**禁止使用 BETA、RC、SNAPSHOT 或 dev 分支** |
| D2 | 跳转服务 `crm-redirect` **独立工程、独立进程**，不依赖 RuoYi 父工程 |
| D3 | 达人端 `kol-portal` 是**独立的轻量 Vue 3 应用**，英文界面 |
| D4 | 定时任务使用 **Spring 自带定时任务 + Redisson 分布式锁**；不使用 SnailJob |
| D5 | 本项目新写的 Excel 代码使用 **FastExcel 稳定版**。若脚手架自带 EasyExcel，**本任务不替换**，只在报告中记录，替换另开任务 |

通用要求：

- 所有依赖只用稳定版，版本号写死，不用范围版本或 `latest`
- Java 代码遵循 Google Java Style；代码、注释、变量名用英文；文档用中文
- 不提交任何密钥；配置敏感项走环境变量，仓库只放 `.env.example`

## 3. 目标目录结构

```
t-crm/
├── backend/          RuoYi-Vue-Plus 6.0.x 后端（+ 新增 ruoyi-crm 业务模块）
├── admin-ui/         与后端同版本的 RuoYi-Vue-Plus 前端（plus-ui）
├── kol-portal/       达人端，Vite + Vue 3 + TypeScript
├── redirect/         crm-redirect 跳转服务，独立 Spring Boot 工程
├── deploy/           Docker Compose、Nginx 配置模板、.env.example
├── docs/             已有文档（除第 9 步的报告外不要改动）
└── README.md         项目说明与本地启动步骤
```

## 4. 执行步骤

每一步完成后都执行该步的"验证"，通过后再进入下一步。验证失败时先自行排查；排查后仍无法解决的，按第 6 节"停止条件"处理。

### 第 1 步：环境检查

- 检查 JDK 21、Maven、Node（LTS）、pnpm 是否可用，记录版本。Docker 不是必需工具，不要安装或启动。
- 检查能否访问 GitHub 或 Gitee（拉取脚手架用）和 Maven / npm 仓库。

**验证**：报告中列出各工具版本；缺失项按停止条件处理。

### 第 2 步：引入后端脚手架

- 从官方仓库 `dromara/RuoYi-Vue-Plus`（GitHub 或 Gitee）获取 6.0.x 系列**最新稳定 tag** 的源码，放到 `backend/`。
- 只取源码，不要嵌套 `.git`（不要用 submodule）。
- 保留原项目 LICENSE 文件；在 `backend/README.md` 开头注明来源仓库、tag 和获取日期。
- 核对该 tag 实际使用的 Spring Boot 与 JDK 版本，如与 D1 不一致，按停止条件处理。

**验证**：`cd backend && mvn -q -DskipTests package` 成功。

### 第 3 步：裁剪后端脚手架

按"能用配置关闭就用配置关闭，能从构建中干净移除就移除，否则保留并记录"的原则处理：

| 模块 | 处理 |
|---|---|
| 多租户 | 用配置关闭（例如 `tenant.enable=false`，以该版本实际配置项为准） |
| 工作流（WarmFlow） | 从构建和管理端依赖中移除；若牵连过多，改为配置关闭并记录 |
| SnailJob（客户端与服务端模块） | 从构建中移除，删除相关配置 |
| 对象存储（OSS） | 若可在不影响其他模块编译的前提下移除则移除，否则保留 |

同时：

- 演示数据、示例模块（如 demo）从构建中移除。
- 不要改动脚手架的权限、登录、数据权限、操作日志等核心机制。

**验证**：`mvn -q -DskipTests package` 成功；报告中逐项列出"已移除 / 已关闭 / 保留"及原因。

### 第 4 步：新增 CRM 业务模块骨架

- 在脚手架业务模块目录（6.0 中通常为 `ruoyi-modules/`）新增 `ruoyi-crm` 模块，并让管理端启动模块依赖它。
- 包名使用脚手架的根包名加 `.crm`，按设计说明书 §2.2 创建子包：`kol`、`product`、`link`、`sheet`、`order`、`commission`、`stats`、`integration`、`task`。
- 每个子包只放一个 `package-info.java`，用一行英文注释说明职责。**不要写任何业务类、实体、Mapper 或接口。**
- 在 `ruoyi-crm` 中新增一个配置类，启用 Spring 定时任务（`@EnableScheduling`）；并确认 Redisson 可用于分布式锁（脚手架已集成则直接复用，不要重复引入）。
- 本任务**不建任何业务表**，不引入 Flyway（后续任务处理）。

**验证**：`mvn -q -DskipTests package` 成功。管理端应用的启动验证在验证机进行（需要 MySQL、Redis），本机不启动。

### 第 5 步：引入管理端前端

- 从官方仓库获取与第 2 步**同版本**的前端工程（plus-ui，Vue 3 + TS + Element Plus），放到 `admin-ui/`，同样不要嵌套 `.git`，保留 LICENSE 并注明来源。
- 前端与后端多租户配置保持一致（关闭租户选择）；移除与已删除后端模块对应的页面入口（工作流、任务调度等）。
- 本地开发代理指向 `http://localhost:8080`（以后端实际端口为准）。

**验证**：`cd admin-ui && pnpm install && pnpm build` 成功。

### 第 6 步：跳转服务 crm-redirect

在 `redirect/` 新建独立的 Spring Boot 工程（与第 2 步相同的 Spring Boot 主版本，JDK 21，Maven）。

功能按设计说明书 §4.5 和 §7.4 实现最小版本：

1. `GET /t/{kolCode}/{productCode}`
   - 路径参数校验：`kolCode` 匹配 `^KOL\d{4,8}$`，`productCode` 匹配 `^[A-Za-z0-9_-]{1,32}$`；不合法直接返回 404。
   - 读取 Redis：`HGET link:{kolCode} {productCode}`，值格式为 `{kolItemId}|{targetUrl}|{status}`。
   - `status == 1`：返回 **302**，`Location` 为 `targetUrl`，并带 `Cache-Control: no-store`。
   - 未命中、格式错误或 `status != 1`：返回 302 到配置项 `crm.redirect.fallback-url`。
   - 命中且跳转成功时，向 Redis Stream `stream:click` 执行 `XADD`，字段：`kolItemId`、`kolCode`、`productCode`、`ts`（毫秒时间戳）、`ipHash`、`ua`、`ref`、`country`。
     - `ipHash` = SHA-256（盐值 + 客户端 IP），盐值来自环境变量；客户端 IP 优先取 `CF-Connecting-IP`，其次 `X-Forwarded-For` 第一个值，最后取连接地址。
     - `country` 取请求头 `CF-IPCountry`，没有则为空字符串。
     - 写 Stream 失败**不能影响跳转**：记录错误日志后照常返回 302。
2. `GET /healthz`：Redis 可连接返回 200，否则返回 503。
3. 不连接 MySQL；Redis 未命中时回源后台的功能**本任务不做**，在代码中用 `// TODO` 注明。
4. 测试分两类，**两类都要写**，下列 6 个场景两类都要覆盖：
   - **单元测试**（不依赖 Docker）：用 MockMvc + 模拟的 Redis 操作对象，默认 `mvn test` 执行；
   - **集成测试**（JUnit 5 + Testcontainers Redis，需要 Docker）：类上标注 `@Tag("docker")`；在 `pom.xml` 中让默认构建通过 surefire 的 `excludedGroups=docker` 排除它们，并新增 Maven profile，id 固定为 **`docker-it`**，启用后只运行带 `docker` 标签的测试（命令：`mvn verify -Pdocker-it`）。本机不运行集成测试，但必须能编译通过。

   覆盖场景：
   - 命中有效链接 → 302 到目标地址，并写入一条 Stream 记录
   - 未命中 → 302 到 fallback
   - `status != 1` → 302 到 fallback
   - 非法路径参数 → 404
   - Stream 写入失败 → 仍然 302 到目标地址
   - `/healthz` 正常 → 200

**验证**：`cd redirect && mvn -q test` 在没有 Docker 的情况下全部通过（集成测试被排除，但已编译）；`mvn -q -DskipTests package` 生成可运行 jar。

### 第 7 步：达人端 kol-portal

在 `kol-portal/` 新建 Vite + Vue 3 + TypeScript 工程：

- 依赖：Element Plus（英文语言包）、vue-router、pinia、axios、ECharts（只安装，不写图表）。
- 只建骨架：基础布局（顶部栏 + 内容区，适配手机宽度）、`/login` 占位页、`/` 占位首页、路由守卫占位（未登录跳转 `/login`，暂用本地状态判断）。
- axios 实例 `baseURL` 为 `/kol-api`；开发环境代理到后端端口。
- 界面文案全部英文。
- 配置 ESLint 与 Prettier。

**验证**：`pnpm install && pnpm build && pnpm lint` 全部通过。

### 第 8 步：本地运行环境与部署模板

在 `deploy/` 下：

- `docker-compose.dev.yml`：仅包含 MySQL 8.4 与 Redis 7（本地开发用），数据卷持久化，端口映射到本机。
- MySQL 首次启动自动导入脚手架提供的 MySQL 初始化脚本（使用第 3 步裁剪后仍需要的脚本；已移除模块的 SQL 不导入）。
- `docker-compose.prod.yml`（模板，暂不需要能直接上线）：nginx、crm-admin、crm-redirect、mysql、redis 五个服务，资源限制参考设计说明书 §10 的内存规划。
- `nginx/` 下提供三个站点的配置模板：`t.域名`（转发 crm-redirect）、`kol.域名`（kol-portal 静态资源 + `/kol-api` 转发后端）、`admin.域名`（admin-ui 静态资源 + 后端 API 转发）。域名用占位变量。
- `.env.example`：列出所有需要的环境变量（数据库、Redis、IP 哈希盐值、fallback 地址等），值留空或给示例。

文件命名固定为：`deploy/docker-compose.dev.yml`、`deploy/docker-compose.prod.yml`、`deploy/.env.example`、`deploy/nginx/`。后端和跳转服务的本地配置要能直接连接 dev 编排中的 MySQL（`localhost:3306`）与 Redis（`localhost:6379`），账号密码从环境变量读取，变量名与 `.env.example` 一致。

**验证**（本机不启动任何容器）：

1. 若本机装有 Docker CLI，执行 `docker compose -f deploy/docker-compose.dev.yml config -q` 和 `docker compose -f deploy/docker-compose.prod.yml config -q` 检查语法；没有则跳过，并在报告中注明；
2. 在报告中写明：dev 编排导入了哪些 SQL 脚本、后端连接 MySQL/Redis 用到的配置文件与环境变量、默认管理员账号名（不要写密码）及登录接口路径，供验证机使用。

容器启动、后端登录、跳转 302 等运行验证，由验证机按《本机验证清单》执行。

### 第 9 步：文档与提交

- 根目录 `README.md`（中文）：项目简介、目录说明、各工程版本、本地启动步骤（数据库 → 后端 → 跳转服务 → 两个前端）、常用命令。
- `docs/04-开发任务/01-项目骨架搭建-执行报告.md`：按第 7 节格式写执行报告。
- 更新根目录 `.gitignore`，确保构建产物、依赖目录、本地数据卷、`.env` 不被提交。
- 提交规范使用 Conventional Commits，建议按工程拆分为多次提交，例如：
  - `chore(backend): import RuoYi-Vue-Plus v6.0.x`
  - `chore(backend): remove tenant, workflow and snail-job modules`
  - `feat(crm): add ruoyi-crm module skeleton`
  - `chore(admin-ui): import plus-ui v6.0.x`
  - `feat(redirect): add click redirect service`
  - `feat(kol-portal): init kol portal skeleton`
  - `chore(deploy): add docker compose and nginx templates`
  - `docs: add skeleton setup report`
- 每条提交信息末尾加上：

  ```
  Co-Authored-By: Codex <noreply@openai.com>
  ```

- 全部步骤完成且本机可执行的验证全部通过后，执行 `git push origin main` 推送到已配置的远程仓库（私有仓库 `ksws00684315/t-crm`）。**不要新增或修改远程仓库配置，不要推送到其他分支或远程。** 若任务按停止条件中止，已完成的提交仍推送，并在报告中注明中止位置。

## 5. 禁止事项

- 不实现第 6 步以外的任何业务功能，不建业务表。
- 不修改 `docs/` 下已有文件。
- 不使用非稳定版本依赖。
- 不提交密钥、令牌、真实账号密码、本地数据库数据卷。
- 不执行 `git push --force`、`git reset --hard`、`git clean -fd`、改写已推送历史等破坏性操作；推送只允许第 9 步规定的 `git push origin main`。
- 不为"以后可能用到"添加额外框架或抽象。

## 6. 停止条件

遇到以下情况，**停止执行并在报告中说明**，不要自行换方案：

- 找不到 RuoYi-Vue-Plus 6.0.x 的正式稳定 tag，或其 Spring Boot / JDK 版本与 D1 不一致；
- 网络无法访问代码仓库或依赖仓库；
- 本机缺少 JDK 21、Maven、Node、pnpm 等必需工具且无法安装（Docker 不在此列）；
- 裁剪某个模块需要改动脚手架核心机制（权限、登录、数据权限、日志）；
- 任一验证步骤在合理排查后仍失败。

## 7. 执行报告格式

`docs/04-开发任务/01-项目骨架搭建-执行报告.md` 包含：

1. **结论**：完成 / 部分完成 / 停止，一句话说明。
2. **环境与版本**：工具版本；脚手架仓库、tag、获取日期；Spring Boot、JDK、Vue、Element Plus 等关键版本。
3. **步骤结果**：每一步做了什么、验证命令及结果（通过 / 失败 + 关键输出）。
4. **脚手架裁剪清单**：模块 → 已移除 / 已关闭 / 保留，及原因。
5. **待验证机执行的项目**：列出本机因无 Docker 未执行的验证，以及验证机需要的信息（SQL 脚本、配置文件、环境变量、管理员账号名、登录接口路径、端口）。
6. **偏离与待办**：与本提示词不一致之处及原因；EasyExcel 情况（D5）；代码中的 `TODO`；需要人工决定的事项。
7. **提交列表**：`git log --oneline` 输出。
