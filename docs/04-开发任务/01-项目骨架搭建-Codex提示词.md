# 任务：搭建海外达人分销 CRM 项目骨架（纯自研）

> 本文件是交给编码代理（Codex）执行的提示词。直接把本文件全文作为任务输入即可。

---

## 1. 背景

你在仓库 `t-crm`（git 仓库，分支 `main`，远程 `origin` 为私有仓库 `ksws00684315/t-crm`）中工作。这是一个海外达人分销 CRM 系统，由一名工程师配合 AI 开发。项目**不使用 RuoYi 等现成后台脚手架**，所有后台基础设施自行实现，目标是代码完全可控、只包含本项目需要的东西。

开始前**先通读**：

- `docs/03-设计/系统详细设计说明书.md`：重点读 §0.2（技术决策）、§1.3（角色）、§2（总体架构）、§3（权限与数据隔离）、§4.5（点击跳转）、§7.4（Redis 链接索引）、§10（非功能）、§12（部署与配置）
- `docs/README.md`：文档索引

**执行环境说明**：执行本任务的机器资源有限，**不能运行 Docker**（也不要尝试安装或启动 Docker）。凡是需要 MySQL、Redis 容器或 Testcontainers 的运行验证，都不在本机执行，而是推送后由另一台机器按 `docs/04-开发任务/01-项目骨架搭建-本机验证清单.md` 验证。因此你要保证：不依赖 Docker 的验证（编译、单元测试、前端构建、静态检查）全部在本机通过；依赖 Docker 的测试代码和配置写好、可以直接运行。

本任务搭建**可编译、可测试的工程骨架和后台基础设施**，不实现达人、商品、订单、佣金、看板等业务功能。唯一的业务例外是第 5 步的跳转服务最小实现。

## 2. 已确定的技术决策（不要更改）

| 编号 | 决策 |
|---|---|
| D1 | **纯自研后端**：Java 21 + Spring Boot **4.1.x 最新补丁版** + Sa-Token + MyBatis-Plus + Flyway + Redisson。不引入任何后台管理脚手架 |
| D2 | 跳转服务 `crm-redirect` **独立工程、独立进程** |
| D3 | 管理端前端基于 **pure-admin-thin**（vue-pure-admin 官方精简版，非国际化版本）；达人端 `kol-portal` 是**独立的轻量 Vue 3 应用**，英文界面 |
| D4 | 定时任务使用 **Spring 自带定时任务 + Redisson 分布式锁** |
| D5 | Excel 使用 **FastExcel 稳定版**（本任务不需要引入，留到用到时） |

通用要求：

- 所有依赖只用**正式稳定版**，版本号写死，禁止 BETA、RC、M、SNAPSHOT、范围版本和 `latest`。
- 引入每个依赖前，确认其稳定版**明确支持 Spring Boot 4.1**（以官方发布说明或 Maven 中央仓库的 starter 为准）。若某依赖没有支持 Spring Boot 4.1 的稳定版，按第 6 节停止条件处理，不要自行换成别的库。
- Java 代码遵循 Google Java Style；代码、注释、变量名用英文；文档用中文。
- Java 根包名：后台 `com.tcrm.admin`，跳转服务 `com.tcrm.redirect`。
- 不提交任何密钥；敏感配置走环境变量，仓库只放 `.env.example`。
- 只实现本提示词要求的内容，不为"以后可能用到"添加抽象或配置项。

## 3. 目标目录结构

```
t-crm/
├── backend/          crm-admin 后台应用（单模块 Spring Boot 工程）
├── admin-ui/         管理端前端（基于 pure-admin-thin）
├── kol-portal/       达人端，Vite + Vue 3 + TypeScript
├── redirect/         crm-redirect 跳转服务，独立 Spring Boot 工程
├── deploy/           Docker Compose、Nginx 配置模板、.env.example
├── docs/             已有文档（除第 8 步的报告外不要改动）
└── README.md         项目说明与本地启动步骤
```

`backend/` 的包结构：

```
com.tcrm.admin
├── TcrmAdminApplication
├── common/        响应格式、异常、分页、校验、Jackson 与时间配置、审计注解与切面、Redis/Redisson 配置
├── security/      Sa-Token 配置、登录、登录失败锁定、当前用户上下文
├── system/        管理员用户、操作日志
└── crm/           业务子包占位：kol、product、link、sheet、order、commission、stats、integration、task
```

## 4. 执行步骤

每一步完成后都执行该步的"验证"，通过后再进入下一步。验证失败时先自行排查；排查后仍无法解决的，按第 6 节"停止条件"处理。

### 第 1 步：环境检查

- 检查 JDK 21、Maven、Node（LTS）、pnpm 是否可用，记录版本。Docker 不是必需工具，不要安装或启动。
- 检查能否访问 GitHub（拉取前端模板用）和 Maven / npm 仓库。
- 确定并记录将使用的版本：Spring Boot 4.1.x、Sa-Token、MyBatis-Plus、Flyway、Redisson、Testcontainers、MySQL 驱动，均需满足第 2 节的版本要求。

**验证**：报告中列出工具版本和依赖版本清单，并注明每个依赖支持 Spring Boot 4.1 的依据。

### 第 2 步：后台应用工程与通用基础设施

在 `backend/` 新建单模块 Spring Boot 工程 `crm-admin`。

**通用基础设施（`common/`）**

1. 统一响应 `R<T>`：`code`、`msg`、`data`；分页响应 `PageResult<T>`：`rows`、`total`。
2. 全局异常处理：业务异常、参数校验异常、未登录、无权限、未知异常分别返回明确的 `code` 和信息；未知异常记录错误日志，不向前端暴露堆栈。
3. 参数校验使用 Jakarta Validation。
4. Jackson：时间统一以 ISO 8601 带时区输出；`BigDecimal` 序列化为字符串；`Long` 类型 ID 序列化为字符串（防止前端精度丢失）。
5. JVM 与数据库时区统一为 UTC，展示时区由前端处理。
6. MyBatis-Plus：分页插件；自动填充 `create_by`、`create_time`、`update_by`、`update_time`；逻辑删除字段 `del_flag`；ID 使用雪花算法。
7. Redis 与 Redisson 连接配置；启用 `@EnableScheduling`；提供一个基于 Redisson 的工具方法，供定时任务获取分布式锁（`tryLock` 带等待和租期参数）。
8. Spring Boot Actuator：只暴露 `health`。

**数据库迁移（Flyway）**

- 迁移脚本位于 `backend/src/main/resources/db/migration`，`V1__init_system.sql` 只建以下两张表：
  - `sys_user`：`id`、`username`（唯一）、`password_hash`、`nickname`、`email`、`role`（`SUPER_ADMIN` / `OPERATOR` / `FINANCE`）、`data_scope`（`ALL` / `OWN`；商务即 `OPERATOR` + `OWN`）、`status`（1 启用 / 0 停用）、`last_login_time`、通用字段（`create_by`、`create_time`、`update_by`、`update_time`、`del_flag`）。
  - `sys_oper_log`：`id`、`user_id`、`username`、`module`、`action`、`method`、`request_uri`、`request_params`（截断到 2000 字符，密码等敏感字段脱敏）、`result`（成功 / 失败）、`error_msg`、`ip`、`cost_ms`、`oper_time`。
- **不建任何业务表。**
- 字符集 `utf8mb4`，引擎 InnoDB。

**超级管理员初始化**

- 应用启动时，若 `sys_user` 中没有超级管理员，则用环境变量 `TCRM_BOOTSTRAP_ADMIN_USERNAME`、`TCRM_BOOTSTRAP_ADMIN_PASSWORD` 创建一个；变量未设置时只打印警告、不创建。
- **SQL 脚本和代码中不得出现任何默认密码。**

**验证**：`cd backend && mvn -q -DskipTests package` 成功。

### 第 3 步：登录、权限与操作日志

**登录（`security/`）**

1. 管理员与达人使用两套独立的 Sa-Token 账号体系：管理员用默认的 `StpUtil`（login type `admin`），另建 `StpKolUtil`（login type `kol`），两者令牌不互通。**本任务只实现管理员登录**；`StpKolUtil` 只建好供后续达人端使用。
2. 接口：
   - `POST /admin-api/auth/login`：用户名 + 密码，BCrypt 校验；成功返回令牌和用户信息（不含密码哈希）。
   - `POST /admin-api/auth/logout`
   - `GET /admin-api/auth/me`：当前用户信息与角色。
   - `PUT /admin-api/auth/password`：修改本人密码（校验旧密码）。
3. 登录失败锁定：同一用户名 15 分钟内失败 5 次，锁定 15 分钟（计数存 Redis）。
4. 停用的用户不能登录；用户被停用后其已有令牌失效。
5. 令牌通过请求头传递；令牌有效期写进配置。

**权限**

1. 使用 Sa-Token 的角色校验注解。角色固定为 `SUPER_ADMIN`、`OPERATOR`、`FINANCE`，**不做动态菜单和动态权限配置**。
2. 提供当前用户上下文：可获取当前用户 ID、角色、数据范围（`ALL` / `OWN`）。后续业务查询据此过滤"商务只看自己负责的达人"，本任务只提供上下文，不实现业务过滤。

**管理员用户管理（`system/`，仅 `SUPER_ADMIN` 可用）**

- `GET /admin-api/system/user`：分页列表，支持用户名、角色、状态筛选
- `POST /admin-api/system/user`：新增
- `PUT /admin-api/system/user/{id}`：编辑昵称、邮箱、角色、数据范围
- `PUT /admin-api/system/user/{id}/status`：启用 / 停用（不能停用自己）
- `PUT /admin-api/system/user/{id}/password`：重置密码

**操作日志**

- 自定义注解 `@AuditLog(module, action)`，切面异步写入 `sys_oper_log`，记录成功与失败。
- 登录、登出、用户管理的所有写操作都加上该注解。
- `GET /admin-api/system/oper-log`：分页查询，仅 `SUPER_ADMIN`。

**测试**

- 单元测试（不依赖 Docker，默认 `mvn test` 执行）：密码校验、登录失败锁定计数逻辑、参数脱敏、权限注解生效（用 MockMvc 加模拟依赖）、异常处理返回格式。
- 集成测试（Testcontainers MySQL + Redis，需要 Docker）：类上标注 `@Tag("docker")`。默认构建通过 surefire 的 `excludedGroups=docker` 排除；新增 Maven profile，id 固定为 **`docker-it`**，启用后只运行带 `docker` 标签的测试（`mvn verify -Pdocker-it`）。至少覆盖：Flyway 迁移成功；超级管理员初始化；登录成功与失败；连续失败锁定；停用用户无法登录；非超级管理员访问用户管理被拒绝；操作日志写入。本机不运行集成测试，但必须能编译通过。

**验证**：`mvn -q test` 在没有 Docker 的情况下全部通过；`mvn -q -DskipTests package` 生成可运行 jar。

### 第 4 步：CRM 业务包占位

在 `com.tcrm.admin.crm` 下按设计说明书 §2.2 创建子包：`kol`、`product`、`link`、`sheet`、`order`、`commission`、`stats`、`integration`、`task`。每个子包只放一个 `package-info.java`，用一行英文注释说明职责。**不要写任何业务类、实体、Mapper 或接口。**

**验证**：`mvn -q -DskipTests package` 成功。

### 第 5 步：跳转服务 crm-redirect

在 `redirect/` 新建独立的 Spring Boot 工程（Spring Boot 版本与 `backend/` 一致，JDK 21，Maven）。

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
4. 测试分两类，下列 6 个场景两类都要覆盖：
   - **单元测试**（不依赖 Docker）：MockMvc + 模拟的 Redis 操作对象，默认 `mvn test` 执行；
   - **集成测试**（Testcontainers Redis）：`@Tag("docker")`，排除规则与 `docker-it` profile 同第 3 步。

   覆盖场景：
   - 命中有效链接 → 302 到目标地址，并写入一条 Stream 记录
   - 未命中 → 302 到 fallback
   - `status != 1` → 302 到 fallback
   - 非法路径参数 → 404
   - Stream 写入失败 → 仍然 302 到目标地址
   - `/healthz` 正常 → 200

**验证**：`cd redirect && mvn -q test` 在没有 Docker 的情况下全部通过；`mvn -q -DskipTests package` 生成可运行 jar。

### 第 6 步：管理端前端 admin-ui

- 从官方仓库 `pure-admin/pure-admin-thin` 获取**最新稳定 tag**（非国际化版本）的源码，放到 `admin-ui/`。不要嵌套 `.git`；保留 LICENSE，在 `admin-ui/README.md` 开头注明来源仓库、tag 和获取日期。
- 删除模板中的演示页面与模拟接口（mock），只保留布局、登录、权限路由等基础能力。
- 对接第 3 步的后端接口：
  - 登录页调用 `/admin-api/auth/login`，保存令牌，请求头携带令牌；
  - 获取 `/admin-api/auth/me`，**按角色静态控制菜单与路由**（不从后端拉取动态菜单）；
  - 令牌失效时跳回登录页。
- 页面：登录、首页占位、修改密码、用户管理（列表、新增、编辑、启停、重置密码；仅超级管理员可见）、操作日志（仅超级管理员可见）。
- 开发代理：`/admin-api` 转发到后端端口。
- 界面文案中文。

**验证**：`cd admin-ui && pnpm install && pnpm build` 成功；模板自带的 lint / 类型检查命令通过。

### 第 7 步：达人端 kol-portal

在 `kol-portal/` 新建 Vite + Vue 3 + TypeScript 工程：

- 依赖：Element Plus（英文语言包）、vue-router、pinia、axios、ECharts（只安装，不写图表）。
- 只建骨架：基础布局（顶部栏 + 内容区，适配手机宽度）、`/login` 占位页、`/` 占位首页、路由守卫占位（未登录跳转 `/login`，暂用本地状态判断）。
- axios 实例 `baseURL` 为 `/kol-api`；开发环境代理到后端端口。
- 界面文案全部英文。
- 配置 ESLint 与 Prettier。

**验证**：`pnpm install && pnpm build && pnpm lint` 全部通过。

### 第 8 步：部署模板、文档与提交

**部署模板（`deploy/`，本机不启动任何容器）**

- `docker-compose.dev.yml`：仅 MySQL 8.4 与 Redis 7（本地开发用），数据卷持久化，端口映射到本机 `3306`、`6379`，带健康检查。数据库结构由后台应用启动时的 Flyway 创建，**不挂载初始化 SQL**。
- `docker-compose.prod.yml`（模板）：nginx、crm-admin、crm-redirect、mysql、redis 五个服务，资源限制参考设计说明书 §10 的内存规划。
- `nginx/`：三个站点配置模板——`t.域名`（转发 crm-redirect）、`kol.域名`（kol-portal 静态资源 + `/kol-api` 转发后台）、`admin.域名`（admin-ui 静态资源 + `/admin-api` 转发后台）。域名用占位变量。
- `.env.example`：列出全部环境变量（数据库、Redis、超级管理员初始化账号、IP 哈希盐值、fallback 地址、各服务端口），值留空或给示例。
- 后台与跳转服务的本地配置能直接连接 dev 编排中的 MySQL 与 Redis，账号密码从环境变量读取，变量名与 `.env.example` 一致。
- 若本机装有 Docker CLI，执行 `docker compose -f deploy/docker-compose.dev.yml config -q` 和 `docker compose -f deploy/docker-compose.prod.yml config -q` 检查语法；没有则跳过并在报告中注明。

**文档**

- 根目录 `README.md`（中文）：项目简介、目录说明、各工程版本、本地启动步骤（数据库 → 后台 → 跳转服务 → 两个前端）、常用命令（含 `mvn verify -Pdocker-it`）。
- `docs/04-开发任务/01-项目骨架搭建-执行报告.md`：按第 7 节格式写执行报告。
- 更新根目录 `.gitignore`，确保构建产物、依赖目录、本地数据卷、`.env` 不被提交。

**提交**

- 使用 Conventional Commits，按工程拆分为多次提交，例如：
  - `feat(backend): init crm-admin with common infrastructure`
  - `feat(backend): add admin auth, user management and audit log`
  - `chore(backend): add crm package placeholders`
  - `feat(redirect): add click redirect service`
  - `chore(admin-ui): import pure-admin-thin <tag>`
  - `feat(admin-ui): wire login, user management and audit log pages`
  - `feat(kol-portal): init kol portal skeleton`
  - `chore(deploy): add docker compose and nginx templates`
  - `docs: add skeleton setup report`
- 每条提交信息末尾加上：

  ```
  Co-Authored-By: Codex <noreply@openai.com>
  ```

- 全部步骤完成且本机可执行的验证全部通过后，执行 `git push origin main`。**不要新增或修改远程仓库配置，不要推送到其他分支或远程。** 若任务按停止条件中止，已完成的提交仍推送，并在报告中注明中止位置。

## 5. 禁止事项

- 不引入 RuoYi 或任何其他后台管理脚手架的后端代码。
- 不实现第 5 步以外的任何业务功能，不建业务表。
- 不做动态菜单、动态权限、多租户、工作流。
- 不修改 `docs/` 下已有文件。
- 不使用非稳定版本依赖。
- 不提交密钥、令牌、真实账号密码、本地数据库数据卷；代码与 SQL 中不出现默认密码。
- 不执行 `git push --force`、`git reset --hard`、`git clean -fd`、改写已推送历史等破坏性操作；推送只允许第 8 步规定的 `git push origin main`。

## 6. 停止条件

遇到以下情况，**停止执行并在报告中说明**，不要自行换方案：

- 第 2 节列出的任一依赖没有支持 Spring Boot 4.1 的稳定版；
- 找不到 pure-admin-thin 的正式稳定 tag，或其依赖要求与本项目冲突；
- 网络无法访问代码仓库或依赖仓库；
- 本机缺少 JDK 21、Maven、Node、pnpm 等必需工具且无法安装（Docker 不在此列）；
- 任一验证步骤在合理排查后仍失败。

## 7. 执行报告格式

`docs/04-开发任务/01-项目骨架搭建-执行报告.md` 包含：

1. **结论**：完成 / 部分完成 / 停止，一句话说明。
2. **环境与版本**：工具版本；依赖版本清单及支持 Spring Boot 4.1 的依据；pure-admin-thin 的 tag 与获取日期；Vue、Element Plus 等前端关键版本。
3. **步骤结果**：每一步做了什么、验证命令及结果（通过 / 失败 + 关键输出）。
4. **接口清单**：已实现的后台接口（方法、路径、所需角色）。
5. **待验证机执行的项目**：列出本机因无 Docker 未执行的验证，以及验证机需要的信息（配置文件、环境变量、端口、登录接口路径）。
6. **偏离与待办**：与本提示词不一致之处及原因；代码中的 `TODO`；需要人工决定的事项。
7. **提交列表**：`git log --oneline` 输出。
