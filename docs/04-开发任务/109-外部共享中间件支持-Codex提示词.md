# 任务 109：本地脚本支持外部共享中间件

> **执行环境**：本机。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/109-external-shared-infra`，分支 `task/109-external-shared-infra`，从最新 `main` 检出。
> - **不连共享中间件**（现在在负责人的云主机上，见 §1），不读主目录的 `deploy/.env.local`。不启动本地后端开发进程。无迁移。不新增依赖。验证用的镜像只在验证环境使用，必须写死版本。
> - 验证用独立资源，名称都带 `t109`：
>   - Docker 模式：中间件项目 `tcrm-t109-infra`，端口 `19934` / `19981`；
>   - 外部模式：模拟云端的独立项目 `tcrm-t109-ext`，端口 `19932` / `19979`；
>   - 预览项目：`tcrm-p109`，端口 `19890`～`19892`；
>   - 网络：`tcrm-t109-net`。
>
>   收尾时只删除这些资源。**不得碰 `tcrm-preview`、`tcrm-local` 网络、容器 `tcrm-postgres` / `tcrm-redis`、launchd 服务 `com.tcrm.aliyun-tunnel`，不得 SSH 到任何主机。**
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - 规范 08 第 9.2 节「共享中间件」；规范 12 第 6 节（`preview.sh up` 验证）；
> - `deploy/README.md`「本地共享中间件与开发」「本地预览」两节；
> - [`遗留问题清单.md`](遗留问题清单.md) 中 L-108 的原文；
> - skill `$preview-verify`、`$browser-acceptance` 里关于中间件的说明。

**参照实现**：`deploy/scripts/local-infra.sh`（现有子命令、`require_env`、备份文件命名）、`deploy/scripts/preview.sh`、`deploy/scripts/check-compose-env.py`。

## 1. 背景

2026-11-04，负责人有意调小了 Docker Desktop 的磁盘，本机所有镜像、容器和数据卷都被清空。负责人决定，共享中间件改用自己云主机上原有的服务：

| | 现状 |
|---|---|
| PostgreSQL | 云主机自带的 **PostgreSQL 16.8**，tcrm 专用的库和角色，已从 11-03 22:07 的备份恢复。负责人已接受版本是 16（CI 和集成测试仍用 17） |
| Redis | 云主机已有的 **Redis 7.2** 实例，**和另一个应用共用 0 号库**（对方的键带 `edict:`、`aiquestion:`、`scrape:` 前缀） |
| 本机访问 | 负责人本机上的 SSH 隧道（launchd 托管），把 `127.0.0.1:55432`、`127.0.0.1:46391` 转到云端。隧道是本机的前提条件，**不进仓库** |
| 预览容器 | `docker-compose.preview.yml` 把地址写死为 `tcrm-postgres:5432`、`tcrm-redis:6379`。架构负责人临时起了两个同名的 socat 转发容器，转到 `host.docker.internal` 上的隧道端口 |

现在的问题：

1. `preview.sh up` 一开始就调用 `local-infra.sh up`，会在本机重新创建 PostgreSQL 和 Redis 容器，和转发容器重名，直接报错退出。
2. `local-infra.sh backup/restore/psql/status/down` 全都基于 `docker compose exec` 本机容器，外部中间件下一个都不能用。
3. 本机直接运行后端不受影响：`.env.local` 的端口就是隧道端口。
4. L-108：在没有 `.env.local` 的工作树里执行 `up`，会自动生成一套随机密码，和已有的数据对不上。

## 2. 要求

### 2.1 模式开关

`.env.local` 新增 `TCRM_LOCAL_INFRA_MODE`，取值 `docker`（默认，即现在的行为）或 `external`。缺省或空值按 `docker` 处理，现有的 `.env.local` 不改也能照常工作。`generate_env` 生成的新文件写入 `TCRM_LOCAL_INFRA_MODE=docker`。

外部模式下，`.env.local` 中已有的 `TCRM_DB_HOST`、`TCRM_DB_PORT`、`TCRM_REDIS_HOST`、`TCRM_REDIS_PORT` 表示**本机能连上的地址**，供本机直接运行的后端使用。缺 `HOST` 时按 `localhost` 处理，与现在 `application.yml` 的默认值一致。

### 2.2 预览 Compose

1. `crm-admin`、`crm-redirect` 两个服务里写死的四个值改成变量，默认值保持现状：
   - `TCRM_PREVIEW_DB_HOST`，默认 `tcrm-postgres`；
   - `TCRM_PREVIEW_DB_PORT`，默认 `5432`；
   - `TCRM_PREVIEW_REDIS_HOST`，默认 `tcrm-redis`；
   - `TCRM_PREVIEW_REDIS_PORT`，默认 `6379`。

   这四个变量只给容器用，和 §2.1 中给本机用的地址分开。
2. 两个服务加上 `extra_hosts: ["host.docker.internal:host-gateway"]`。Docker Desktop 本来就能解析这个地址，加上后在 Linux 上也能用。
3. 外部网络 `${TCRM_LOCAL_INFRA_NETWORK:-tcrm-local}` 保留，不改结构。
4. `check-compose-env.py` 要能通过。新变量都有默认值，不必进 `.env.example`；脚本要求进 `.env.example` 的话，照脚本的要求处理。

### 2.3 `local-infra.sh`

| 子命令 | docker 模式 | external 模式 |
|---|---|---|
| `up` | 不变 | 不创建任何中间件容器。确保 `TCRM_LOCAL_INFRA_NETWORK` 网络存在（不存在才创建，不删除已有的）。然后检查连通性：PostgreSQL 用账号执行 `select 1`，Redis 执行 `PING`。客户端都用写死版本的临时容器（`postgres:17.11`、`redis:7.4.5`），经 `host.docker.internal` 和本机端口连接。连不上时报错退出，提示「请确认本机到外部中间件的隧道或网络」，不在仓库里写死任何隧道服务名或主机名 |
| `status` | 不变 | 输出连通性，以及 PostgreSQL 的 `server_version`、Redis 的 `redis_version`。不输出密码和连接串 |
| `down` | 不变 | 什么也不做，提示「外部中间件不由本脚本管理」，退出码 0 |
| `backup` | 不变 | 只备份数据库：用 `postgres:17.11` 客户端执行 `pg_dump --no-owner`，文件命名、权限与现在一致。**不备份 Redis**（实例与其他应用共用，不能 `SAVE`，也不能导出全部键），输出一行说明：恢复数据库后，链接索引用管理端「重建链接索引」（`POST /admin-api/link/index-rebuilds`）恢复，会话需要重新登录 |
| `restore <文件>` | 不变 | 先 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`，再导入，用 `postgres:17.11` 客户端，保留 yes 确认。服务器主版本低于 17 时，导入前过滤掉 `SET transaction_timeout` 这一行。`\restrict` 由 17 客户端处理，不用过滤。**不碰 Redis**，即使有同名 `.rdb` 也不恢复，并输出说明 |
| `psql` | 不变 | 用 `postgres:17.11` 客户端交互连接 |
| `migrate-from-preview` | 不变 | 拒绝执行，提示外部模式不适用 |

要求：
- 密码只能通过环境变量（`PGPASSWORD`、`REDISCLI_AUTH`）传给临时容器，不出现在命令行参数、日志和报错里；脚本不打开 `set -x`。
- 临时客户端容器一律 `--rm`。

### 2.4 `preview.sh`

外部模式下，`up` 调用 `local-infra.sh up` 时只做 §2.3 的连通性检查和网络准备，不创建中间件容器。如果 `.env.local` 没有写 `TCRM_PREVIEW_*`，`preview.sh` 自动按 `host.docker.internal` 和 `.env.local` 中的 `TCRM_DB_PORT`、`TCRM_REDIS_PORT` 传给 Compose；写了就以 `.env.local` 为准。其他步骤不变（构建静态文件、`compose up -d --build`、健康检查、reload nginx、验证码接口探测）。`down`、`reset` 不变。

### 2.5 L-108：不在错误的位置生成新配置

`generate_env` 只在以下情况全部满足时才生成：

1. `ENV_FILE` 不存在；
2. 当前不在 git 工作树里，即 `git rev-parse --git-dir` 和 `--git-common-dir` 指向同一处；
3. 没有 `${项目}_pgdata` 或 `${项目}_redisdata` 数据卷。

任一条件不满足就拒绝生成，提示改用主目录的 `deploy/.env.local`（`TCRM_LOCAL_ENV_FILE=/主目录/deploy/.env.local`），退出码非 0。验证时需要在工作树里生成新文件的，用一个显式的环境变量放行（例如 `TCRM_LOCAL_ALLOW_GENERATE=yes`），并且只在验证中使用。

### 2.6 文档

1. `deploy/README.md`「本地共享中间件与开发」：
   - 加一小节「外部中间件模式」，写清开关、本机地址与容器地址的区别、各子命令在外部模式下的行为（照 §2.3 表格）、备份不含 Redis 的原因，以及恢复后要重建链接索引；
   - 说明隧道是本机前提条件，不由仓库管理。
2. 规范 08 第 9.2 节：
   - 「Compose 项目 `tcrm-local`，端口 55432 / 46391」改为：中间件可以是本机 Compose 项目，也可以是外部实例，由 `.env.local` 的 `TCRM_LOCAL_INFRA_MODE` 决定；本机访问端口不变；
   - 「派发前执行 `local-infra.sh backup`」保留，两种模式都适用。
3. `本机任务通用要求.md` 第 2 节「共享中间件」一条，按同样口径改，并补一句：工作树里运行脚本时，用 `TCRM_LOCAL_ENV_FILE` 指向主目录的 `deploy/.env.local`（L-108）。
4. skill `$preview-verify`、`$browser-acceptance` 中关于中间件的说明同步。`browser-acceptance` 里「Compose 项目 `tcrm-local`」的说法要改。

## 3. 不做

- 不改应用代码和 `application.yml`。不加 Redis 库号配置（另立任务讨论）。
- 隧道、launchd 配置、云主机上的任何东西都不进仓库，也不在仓库里写主机地址。
- 不改 `docker-compose.dev.yml` 的结构；docker 模式的行为保持现状。
- 不改生产 Compose。
- 不修改 `docs/04-开发任务/README.md`、`遗留问题清单.md`（由架构负责人在合并时更新）。

## 4. 验证

所有环境文件放在工作树 `.tmp/`，不提交。通过 `TCRM_LOCAL_ENV_FILE`、`TCRM_LOCAL_INFRA_PROJECT`、`TCRM_LOCAL_INFRA_NETWORK`、`TCRM_LOCAL_INFRA_DB_PORT`、`TCRM_LOCAL_INFRA_REDIS_PORT`、`TCRM_PREVIEW_PROJECT` 和预览端口变量，指向本任务自己的资源。

1. **docker 模式回归**（项目 `tcrm-t109-infra`）：依次执行 `up`（用放行变量生成新文件）→ `status` → `backup` → 往库里写一行 `t109_` 数据 → `restore` 刚才的备份 → 确认这一行没有了、配套 RDB 也已恢复 → `down`。结果和改动前一致。
2. **模拟外部中间件**：
   - 起项目 `tcrm-t109-ext`：`postgres:16.8` 加 `redis:7.2.3`，只绑定 `127.0.0.1:19932` / `19979`，用来模拟云端的版本差异；
   - 在 Redis 0 号库预先写入一个 `edict:t109-foreign` 键，代表另一个应用的数据；
   - 外部模式的环境文件指向这两个端口。
3. **外部模式各子命令**：
   - `up` 成功，不创建任何中间件容器（列出执行前后 `docker ps -a` 的对比）；
   - `status` 输出两个服务的版本；
   - `down` 什么也不做；
   - `backup` 只生成 `.sql.gz`；
   - 用**第 1 步在 PostgreSQL 17 上导出的备份** `restore` 到 16 成功，证明过滤生效；
   - `restore` 前后 `edict:t109-foreign` 都在，值没变；
   - `psql` 能连上；
   - `migrate-from-preview` 被拒绝。
4. **外部模式预览**：
   - 环境文件不写 `TCRM_PREVIEW_*`，执行 `preview.sh up`（项目 `tcrm-p109`、端口 `19890`～`19892`、网络 `tcrm-t109-net`）：后台和跳转服务健康，三个入口返回 200，后端日志显示连的是 PostgreSQL 16.8；
   - 再写上 `TCRM_PREVIEW_*` 指向同一处，重跑一次，确认以 `.env.local` 为准。
5. **连不上时**：停掉 `tcrm-t109-ext` 的 PostgreSQL，`up` 和 `preview.sh up` 都给出 §2.3 的提示，退出码非 0，输出里没有密码。
6. **L-108**：
   - 在工作树里、不设 `TCRM_LOCAL_ENV_FILE`、不放行时执行 `up`，被拒绝，没有生成文件；
   - 已有 `tcrm-t109-infra_pgdata` 数据卷时，即使放行也拒绝。
7. **凭证不泄露**：把第 3～5 步的全部输出存下来，搜索环境文件里的两个密码，结果为 0。

## 5. 验收与交付

- **检查**：
  - Deploy CI 工作流里的全部步骤（`check-healthchecks.py`、`check-compose-env.py`、各 `test-*.sh`）；
  - 改动的脚本过 `shellcheck`；
  - `docker compose -f deploy/docker-compose.preview.yml config` 在两种模式下都能通过。

  不跑 `mvn`、前端检查。检查交给子代理。
- **浏览器验收**：不涉及界面，不做。
- **执行报告**：`docs/04-开发任务/109-外部共享中间件支持-执行报告.md`，逐条复核表覆盖 §2 每一项。§4 每一步写清命令和结果，附清理命令。
- **PR**：标题「fix(deploy): 本地脚本支持外部共享中间件（任务 109）」。不合并、不推 main。推送后看一次 `gh pr checks`，结果写进报告，不轮询。

## 6. 停下来报告

- `postgres:17.11` 客户端导入 16 时，除 `SET transaction_timeout` 外还有别的语句失败；
- 外部模式下 `DROP SCHEMA public` 因权限失败（外部库的 `public` 属主不是应用角色）；
- `host.docker.internal` 在预览容器里解析不了，或 `host-gateway` 与现有网络配置冲突；
- 需要改应用代码、`application.yml` 或生产 Compose。
