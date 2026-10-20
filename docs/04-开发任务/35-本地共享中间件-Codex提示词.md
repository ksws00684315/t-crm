# 任务 35（部署）：本地共享中间件——预览、开发、代理联调共用一套 PostgreSQL 与 Redis

> 本文件是交给编码代理执行的提示词，把全文作为任务输入即可。
>
> **执行环境**：本机执行。独立工作树 `.worktrees/35-shared-infra`，分支 `task/35-shared-infra`，已从最新的 `main` 检出。需要 Docker；验证预览时还需要 Node 24、pnpm。只改 `deploy/`、`.gitignore` 和文档，不改业务代码。
>
> **CI 说明**：GitHub Actions 暂停使用，**不要等待** `gh pr checks`。
>
> 本任务是负责人 2026-10-20 提出的部署调整，不在 1018 表的功能项里。

---

## 1. 背景与决定

现在每个环境各自起一套 PostgreSQL 和 Redis：

- 负责人的本地预览：Compose 项目 `tcrm-preview`，有自己的数据卷；
- 本地开发：`docker-compose.dev.yml`；
- 各任务代理：用 `docker-compose.dev.yml` 起 `tcrm-NN` 项目。

数据互不相通，每次都要重新造数据。

负责人决定（不要自行改变）：

1. PostgreSQL 和 Redis 作为**一套独立运行的容器**（下称「共享中间件」）。负责人的本地预览、本地开发、各任务代理的联调和浏览器验证，**都连这一套，并且共用同一个数据库**，数据是同一份。
2. 自动化集成测试（`mvn verify -Pdocker-it`，Testcontainers）**不改**，仍然使用临时容器。
3. 生产部署模板 `docker-compose.prod.yml` **不改**。

共用同一个数据库有风险：代理分支上的迁移会进入共享库，测试数据会混进来。负责人已知道这些风险，本任务通过第 3 节的保护措施降低风险。

## 2. 必读

`AGENTS.md`、`docs/05-工程规范/`（重点 06、08）；`deploy/README.md`、`deploy/docker-compose.dev.yml`、`deploy/docker-compose.preview.yml`、`deploy/scripts/preview.sh`、`deploy/.env.example`；`docs/04-开发任务/27-本地预览环境-Codex提示词.md`。

## 3. 要做的事

### 3.1 共享中间件

- 把 `deploy/docker-compose.dev.yml` 改造为共享中间件：
  - Compose 项目名固定为 `tcrm-local`；
  - 服务：`postgres:17.11`、`redis:7.4.5`，版本与现在相同；
  - 命名数据卷；Redis 必须设密码，开启 AOF；
  - 宿主机端口只绑定 `127.0.0.1`，默认 **PostgreSQL 55432、Redis 46391**（负责人原来的开发端口）；
  - 声明一个固定名称的 Docker 网络 `tcrm-local`，让预览的后端容器能加入，并通过固定的服务别名访问（例如 `tcrm-postgres`、`tcrm-redis`）。
- 本地统一的环境文件 `deploy/.env.local`（已被 `.gitignore` 的 `.env.*` 忽略，确认一下），包含：
  - 数据库账号密码、Redis 密码；
  - **加密主密钥 `TCRM_SECRET_KEY`、IP 哈希盐 `TCRM_IP_HASH_SALT`**：共用一个数据库，就必须共用这两个值，否则读不出已加密的凭证；
  - 首次平台管理员账号；
  - 三个对外地址的本地值；
  - 第 3.3 节的 Flyway 本地设置。
  - 预览、开发和代理都从这个文件读取。文件权限 600。
- 脚本 `deploy/scripts/local-infra.sh`，子命令：
  - `up`：`deploy/.env.local` 不存在时生成，所有密码和密钥随机生成；然后启动共享中间件并等到健康；
  - `down`：停止容器，保留数据；
  - `status`；
  - `backup`：用 `pg_dump` 备份到 `deploy/local-backups/`（加入 `.gitignore`），文件名带时间，同时导出 Redis RDB；
  - `restore <文件>`：恢复前要求输入 `yes` 确认；
  - `psql`：进入数据库命令行。
  - **没有删除数据卷的子命令**。确实需要清空时，README 写明手动命令，并提醒先备份。

### 3.2 预览改用共享中间件

- `docker-compose.preview.yml` 删除自己的 postgres、redis 服务和数据卷。`crm-admin`、`crm-redirect` 加入外部网络 `tcrm-local`，用共享中间件的别名连接。
- `preview.sh` 改为读取 `deploy/.env.local`：
  - `up`：共享中间件没启动时，先执行 `local-infra.sh up`；
  - `down`：只停预览自己的容器，不动中间件；
  - `reset`：只删除预览的镜像，**不删除共享数据**。
- 预览端口仍然是 8890～8892。另外允许通过环境变量临时覆盖：`TCRM_PREVIEW_ADMIN_PORT` 等三个端口，以及共享中间件的项目名、网络名、端口，用于本任务自测和以后排障。默认值不变。

### 3.3 本地 Flyway 设置（降低共用数据库的风险）

- 在 `deploy/.env.local` 中设置以下两项，只用于本地，生产模板和 `.env.example` 的生产部分不加：
  - `SPRING_FLYWAY_OUT_OF_ORDER=true`；
  - `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:missing`。
- 效果：数据库里有当前代码没有的迁移（来自其他分支）时，后端仍然可以启动；分支先后合并时，较早版本号的迁移也能补跑。
- README 写明这只能缓解问题：分支迁移删掉的列、改掉的约束，不会因此恢复，需要用 `backup` / `restore` 处理。

### 3.4 迁移负责人现有的预览数据

负责人现在的预览数据在数据卷 `tcrm-preview_pgdata`、`tcrm-preview_redisdata` 中，账号与密钥在 `deploy/.env.preview` 中。

- 提供一次性的子命令 `local-infra.sh migrate-from-preview`：
  1. 先执行 `backup`；
  2. 把 `.env.preview` 中的数据库账号密码、Redis 密码、`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、管理员账号等写入 `.env.local`，保证旧数据和旧凭证能继续使用；
  3. 停掉旧的预览容器；
  4. 把旧数据卷的数据复制到共享中间件的数据卷；
  5. 启动共享中间件，校验数据完整（达人数、用户数一致）。
  - 旧数据卷**不删除**，由负责人确认后手动删除。
- **你不要对负责人正在使用的 `tcrm-preview` 执行这个命令**。自测时，用覆盖变量起一套独立的「旧预览」（例如项目名 `tcrm-p35`，用其他端口），造几条数据，再迁移到独立的共享中间件（例如项目名 `tcrm-local35`、端口 55465 / 46465、网络 `tcrm-local35`），验证整个流程。负责人的环境由架构负责人在合并后切换。

### 3.5 本地开发与代理的用法（文档）

`deploy/README.md` 改写「本地开发」一节，写明：

- 启动共享中间件（`local-infra.sh up`），本地运行后端的方法：从 `deploy/.env.local` 导出变量，主机为 `localhost`，端口 55432 / 46391；Vite 开发服务器照旧。
- **给代理的规则**：
  - 联调与浏览器验证连共享中间件，用 `deploy/.env.local`；
  - 后端和 Vite 用各自任务分配的端口；
  - 测试账号、测试达人统一加任务前缀，例如用户名 `t34_…`、邮箱 `t34_…@example.com`；
  - **收尾时只删除自己造的数据，不得停止或删除共享中间件的容器和数据卷，不得 `down -v`**；
  - 派发前，架构负责人会先执行 `backup`。
- 写明自动化集成测试仍然用 Testcontainers，与共享中间件无关。

### 3.6 其他引用

- 更新 `.github/workflows/deploy.yml` 中对 `docker-compose.dev.yml` 的校验命令（文件仍保留，只是内容变了）。
- 检查 `docs/` 中提到旧用法的地方：`docker compose -p tcrm-NN -f deploy/docker-compose.dev.yml … down -v`。已合并任务的提示词和报告**不改**，只改 `deploy/README.md` 和 `docs/05-工程规范/` 中的现行说明（如果有）。

## 4. 检查与复验：用子代理完成

**检查和验证交给子代理执行**（使用你所在工具的子代理功能，例如 Claude Code 的 Agent / Task 工具）。主代理负责开发、汇总结果和修复问题：

1. **检查子代理**：
   - `docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env.example config -q`，preview、prod 两个 Compose 文件同样执行；
   - `bash -n` 检查所有脚本，有 `shellcheck` 时也跑一遍；
   - `python3 deploy/scripts/check-healthchecks.py`。
2. **流程验证子代理**：全部使用 3.4 节说的独立项目名和端口，不碰负责人的环境。逐项验证并记录命令与结果：
   - `local-infra.sh up`（首次生成 `.env.local`）、`status`、`down`、再次 `up` 后数据仍在；
   - `backup` / `restore`；
   - 预览接共享中间件启动，三个地址可以访问；
   - 本地运行后端连共享中间件，与预览读到同一份数据；
   - 在共享库中预置一条当前代码没有的迁移记录，后端仍然能启动（验证 3.3）；
   - `migrate-from-preview` 完整流程，迁移前后达人数、用户数一致，旧数据卷保留；
   - 预览的 `down` / `reset` 不影响共享数据。
3. **复核子代理**：只读。对照本提示词第 1、3 节逐条检查，返回「要求 → 实现位置 → 是否满足 → 差异」清单。

## 5. 交付

- 执行报告 `docs/04-开发任务/35-本地共享中间件-执行报告.md`：新的结构图（哪些容器、哪个网络、哪些端口）、命令清单、验证结果、迁移步骤，以及架构负责人切换负责人环境时要执行的命令序列。
- 收尾：删除自测用的独立项目的容器、网络、数据卷（只删你创建的，名称带 35），删除临时文件；在报告中列出清理命令。**不要动** `tcrm-preview`、`tcrm-local` 以及其他人的容器和数据卷。
- 提交遵循 Conventional Commits（英文），推送分支，按 PR 模板创建 PR，PR 描述中贴出子代理的结果。**不要合并 PR，不要推送 `main`。**

## 6. 禁止事项与停止条件

**禁止**：

- 修改业务代码、生产 Compose 模板；新增依赖；
- 对负责人的 `tcrm-preview` 数据卷执行迁移、删除或改写；
- 推送 `main`、合并 PR、rebase、`push --force`、`reset --hard`；
- 全局 Docker 清理。

**停下来报告**：

- 预览容器无法加入外部网络，或共享中间件无法同时服务预览与本机开发；
- 数据卷复制在 PostgreSQL 17.11 同版本之间仍然失败；
- 合理排查后验证仍然失败。

完成后回复一段总结：PR 链接、结构与命令、子代理验证结果、负责人环境的切换步骤、遗留问题。
