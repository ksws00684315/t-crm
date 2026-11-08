# 部署模板（deploy）

| 文件 | 用途 |
|---|---|
| `RUNBOOK.md` | 生产装机与上线手册：从空白主机到上线检查通过的完整操作顺序（目录规划、`.env` 生产取值、root crontab 整块、上线前核对表、演练记录表） |
| `.env.example` | 全部环境变量（名称与 backend / redirect 实际使用的一致）；复制为 `deploy/.env` 后填写，`.env` 不入库 |
| `docker-compose.dev.yml` | 本地共享中间件：PostgreSQL 17.11、Redis 7.4.5 |
| `docker-compose.prod.yml` | 生产**模板**：nginx、crm-admin、crm-redirect、postgres、redis；应用与数据库镜像从仓库拉取，不在服务器构建 |
| `docker-compose.preview.yml` | 本地预览：与生产相同的五个服务，只绑定 `127.0.0.1:8890/8891/8892` |
| `nginx/` | 四个站点（`t.`、`kol.`、`admin.`、`upload.`）的配置模板、TLS 与安全响应头片段、Cloudflare 回源 IP 段与回源认证 CA；`nginx/preview/preview.conf` 是本地预览（仅 HTTP） |
| `postgres/initdb/` | 生产 PostgreSQL 首次初始化脚本：创建非超级用户的应用账号并设为库的 owner |
| `scripts/renew-public-cert.sh` | 申请与续期 `admin.`、`upload.` 的 Let's Encrypt 公开证书（DNS-01），放到证书目录并重载 Nginx |
| `scripts/ops-check.sh` | 主机监控检查（host cron 每 5 分钟）：磁盘、内存、容器、公开证书、备份、备份凭证、应用日志、容量告警；告警走 Webhook，心跳供外部探活（见「监控告警」） |
| `scripts/preview.sh` | 本地预览：`up` / `down` / `reset` |
| `scripts/local-infra.sh` | 本地共享中间件的启动、停止、备份、恢复与维护命令 |
| `backup/pg-backup.sh` | 每日加密 `pg_dump` 备份脚本模板（保留 7 日 + 4 周，可选上传 S3） |
| `backup/pgbackrest-backup.sh` | pgBackRest 定时备份（周日全量、其余日差异，结束前 `check`） |
| `backup/restore-pitr.sh` | 时间点 / 最新点恢复脚本（需输入 `yes`） |
| `backup/iam-policy.example.json` | 备份写入身份的 IAM 最小权限策略示例 |
| `postgres/` | 带 pgBackRest 的 PostgreSQL 镜像（Dockerfile、`pgbackrest.conf`）与初始化脚本 |
| `scripts/build-static.sh` | 构建两个前端并生成静态目录（含四份第三方许可声明）；发布流程在 CI 中运行它，本地预览也用它 |
| `scripts/release.sh` | 生产发布与回滚：`deploy <版本>`、`rollback <版本>`、`status`（见「发布与回滚」） |
| `static/Dockerfile` | 静态产物镜像（`FROM scratch`），由发布流程构建，`release.sh` 从中解出前端文件 |
| `../.github/workflows/release.yml` | 版本标签触发：构建 crm-admin、crm-redirect、crm-postgres、crm-static（linux/amd64）并推送到 GHCR |
| `../backend/Dockerfile`、`../redirect/Dockerfile` | 多阶段构建；运行镜像为 JRE 21，非 root 用户；构建上下文是仓库根目录 |

## 本地共享中间件与开发

```bash
deploy/scripts/local-infra.sh up       # 首次生成 deploy/.env.local（权限 600）并启动 PostgreSQL、Redis
deploy/scripts/local-infra.sh status
deploy/scripts/local-infra.sh backup
deploy/scripts/local-infra.sh down     # 只停止容器，保留数据
```

该项目固定为 `tcrm-local`，网络为 `tcrm-local`，数据卷为 `tcrm-local_pgdata` 和 `tcrm-local_redisdata`。宿主端口只绑定 `127.0.0.1:55432`（PostgreSQL）和 `127.0.0.1:46391`（Redis）。`deploy/.env.local` 保存数据库和 Redis 凭证、加密主密钥、IP 哈希盐及初始管理员凭证，首次随机生成后后续启动不覆盖。预览容器通过 `tcrm-postgres:5432`、`tcrm-redis:6379` 访问同一实例；本机运行的后台和跳转服务从该文件导出变量，以 `localhost` 和上述端口连接。后台本地运行示例：

```bash
set -a
source deploy/.env.local
set +a
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Vite 开发服务器仍按 `admin-ui/`、`kol-portal/` 各自原有命令启动。Flyway 本地配置（`SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:missing,*:future`、`SPRING_FLYWAY_OUT_OF_ORDER=true`）允许忽略其他分支产生但当前代码没有的迁移，并按顺序补跑较早版本迁移。库中这类迁移的版本低于当前代码最新迁移时，Flyway 视为 `missing`，高于时视为 `future`，两种都要忽略，否则其他分支先跑了更新的迁移后，当前代码的后台会校验失败、无法启动。这只能缓解版本先后和缺失问题；删除的列或改掉的约束不会自动恢复，需要用 `backup` / `restore` 处理。

### 代理联调规则

- 联调与浏览器验证统一使用 `deploy/.env.local` 和共享中间件；后端、Vite 使用各自任务分配的端口。
- 测试账号和测试达人统一加任务前缀，例如用户名 `t34_…`、邮箱 `t34_…@example.com`。
- 收尾时只删除自己创建的数据；不得停止或删除共享中间件的容器和数据卷，不得执行 `down -v`。派发任务前，架构负责人会先运行 `local-infra.sh backup`。
- 自动化集成测试 `mvn verify -Pdocker-it` 仍使用 Testcontainers 临时容器，与共享中间件无关。

维护命令：

```bash
deploy/scripts/local-infra.sh psql
deploy/scripts/local-infra.sh restore deploy/local-backups/tcrm-local-<时间>.sql.gz  # 输入 yes 确认
```

备份写入带时间戳的 PostgreSQL SQL 压缩文件和 Redis RDB 到已忽略的 `deploy/local-backups/`。用同名 `.sql.gz` 恢复时，脚本也会恢复配套 `.rdb`：停止 Redis、替换 RDB、清理旧 AOF 状态，再用临时 Redis 7.4 根据备份 RDB 重建 AOF 后启动共享 Redis。脚本没有删除数据卷的命令。确需清空时，先备份，再手动执行：

```bash
docker compose -p tcrm-local -f deploy/docker-compose.dev.yml --env-file deploy/.env.local down
docker volume rm tcrm-local_pgdata tcrm-local_redisdata
```

这会永久删除共享数据。

多个本地后台进程共用数据库时，系统配置缓存按进程维护；在一个后台修改后会立即刷新该进程和跳转服务的 Redis 回退地址，其他后台进程需重启后读取新值。生产按单后台实例部署，不受此限制。

### 外部中间件模式（任务 109）

`.env.local` 的 `TCRM_LOCAL_INFRA_MODE` 决定中间件来源：`docker`（缺省或空值，即上面的本机 Compose 项目）或 `external`（本机之外的实例，例如云主机上的 PostgreSQL 16 与 Redis 7.2，经本机 SSH 隧道访问）。现有的 `.env.local` 不写这一项也照常按 `docker` 工作；`local-infra.sh up` 新生成的文件固定写 `TCRM_LOCAL_INFRA_MODE=docker`。

两种地址是分开的：

- **本机地址**：`.env.local` 里的 `TCRM_DB_HOST`、`TCRM_DB_PORT`、`TCRM_REDIS_HOST`、`TCRM_REDIS_PORT`，供本机直接运行的后端和跳转服务使用。外部模式下它们是**本机能连上的地址**（即隧道端口）；缺 `HOST` 时按 `localhost` 处理。
- **容器地址**：预览 Compose 里的 `TCRM_PREVIEW_DB_HOST` / `TCRM_PREVIEW_DB_PORT` / `TCRM_PREVIEW_REDIS_HOST` / `TCRM_PREVIEW_REDIS_PORT`，默认 `tcrm-postgres:5432`、`tcrm-redis:6379`（指向本机 Compose 项目）。外部模式下 `preview.sh up` 在 `.env.local` 未写这四项时自动按 `host.docker.internal` 加 `TCRM_DB_PORT`、`TCRM_REDIS_PORT` 传入；写了就以 `.env.local` 为准。

外部模式下各子命令的行为：

| 子命令 | 外部模式行为 |
|---|---|
| `up` | 不创建任何中间件容器；确保 `TCRM_LOCAL_INFRA_NETWORK` 网络存在（不存在才创建），再用写死版本的临时客户端容器（`postgres:17.11`、`redis:7.4.5`）经 `host.docker.internal` 和本机端口做连通性检查（PostgreSQL `select 1`、Redis `PING`）。连不上时报错退出，提示确认本机到外部中间件的隧道或网络 |
| `status` | 输出连通性和 PostgreSQL `server_version`、Redis `redis_version`，不输出密码和连接串 |
| `down` | 什么也不做，提示「外部中间件不由本脚本管理」，退出码 0 |
| `backup` | 只备份数据库（`pg_dump --no-owner`），文件命名、权限与 docker 模式一致 |
| `restore <文件>` | yes 确认后先 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` 再导入；服务器主版本低于 17 时过滤 `SET transaction_timeout`；不碰 Redis |
| `psql` | 用 `postgres:17.11` 临时容器交互连接 |
| `migrate-from-preview` | 拒绝执行，外部模式不适用 |

外部模式**不备份、不恢复 Redis**：云端实例与其他应用共用（对方键带 `edict:` 等前缀），不能对它执行 `SAVE`，也不能导出全部键。恢复数据库后，链接索引用管理端「重建链接索引」（`POST /admin-api/link/index-rebuilds`）恢复，所有会话需要重新登录。

到外部中间件的隧道（例如 SSH 端口转发）是**本机的前提条件**，由负责人的本机管理，不由仓库管理，仓库中也不写死任何隧道服务名或主机名。

L-108：`up` 只在环境文件不存在、且当前不在 git 工作树中、也没有同项目的 `pgdata`/`redisdata` 数据卷时才生成新配置；否则拒绝并提示改用主目录的 `deploy/.env.local`（`TCRM_LOCAL_ENV_FILE=/主目录/deploy/.env.local`）。在任务工作树里运行脚本时，始终用 `TCRM_LOCAL_ENV_FILE` 指向主目录的文件。

## 本地预览

只用于在本机查看**当前工作目录**的源码，不用于任何服务器。从源码构建并启动管理端、达人端、后台和跳转服务，共用本地共享中间件的 PostgreSQL 与 Redis。

前置条件：Docker（含 Compose 插件）、Node.js、pnpm、openssl、curl。

```bash
deploy/scripts/preview.sh up      # 构建并启动。可在任意目录执行（脚本按自身位置定位仓库根）
deploy/scripts/preview.sh down    # 只停止预览容器；不停止共享中间件
deploy/scripts/preview.sh reset   # 删除本预览项目容器和本地镜像；须输入 yes，不改共享数据
```

| 站点 | 地址 |
|---|---|
| 管理端 | http://localhost:8890 |
| 达人端 | http://localhost:8891 |
| 跳转服务 | http://localhost:8892 （`/t/…`、`/i/…`、`/healthz`） |

预览端口默认是 `8890`、`8891`、`8892`，Compose 项目名默认 `tcrm-preview`。需要临时并行运行或排障时，可设置 `TCRM_PREVIEW_ADMIN_PORT`、`TCRM_PREVIEW_KOL_PORT`、`TCRM_PREVIEW_REDIRECT_PORT`，以及共享中间件的 `TCRM_LOCAL_INFRA_PROJECT`、`TCRM_LOCAL_INFRA_NETWORK`、`TCRM_LOCAL_INFRA_DB_PORT`、`TCRM_LOCAL_INFRA_REDIS_PORT`。并行预览若遇到网段冲突，可设置 `TCRM_PREVIEW_SUBNET`，其默认值为 `172.30.0.0/24`，代理信任范围会同步使用该值。默认共享网络为 `tcrm-local`，PostgreSQL 和 Redis 的宿主端口为 `55432` / `46391`。

预览与本机开发共用 `deploy/.env.local` 的数据库账号、Redis 密码、`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT` 和平台管理员账号。不要替换主密钥或 IP 哈希盐，否则旧凭证无法解密或旧哈希无法匹配。文件权限 `600`，与构建产物 `deploy/www-preview/` 和备份文件都不入库。

首次使用：用平台管理员登录管理端（登录验证码保持开启，与生产一致）→ 在平台管理里为默认公司创建超级管理员 → 退出后用超级管理员登录。预览不做演示数据。

预览地址由环境变量提供系统配置默认值；可在平台管理员的「系统配置」里改为 `http://localhost[:端口]` 并立即生效。`TCRM_MAIL_API_KEY` 为空，邮件不真正发出，只走日志兜底，且默认不记录正文（生产禁止打开 `crm.mail.log-links`）。

看某个分支：切到该分支或进入其工作树，再执行 `preview.sh up`。命令在重建后端后重新加载 Nginx 的上游地址，并通过管理端验证码接口验证反向代理；若后端健康但验证码返回 502，可重新执行 `preview.sh up`。不同工作树的预览容器共用同一套数据库。Flyway 设置只能缓解分支迁移的版本顺序与记录缺失；如果迁移造成结构破坏，先备份并恢复兼容的备份。

### 从旧预览迁移

架构负责人切换负责人环境时，在旧预览数据所在目录核对目标项目和旧卷后，执行一次性迁移。默认 `tcrm-preview` 需要负责人显式授权；旧卷保留到负责人确认后手动删除：

```bash
TCRM_ALLOW_OWNER_PREVIEW_MIGRATION=yes deploy/scripts/local-infra.sh migrate-from-preview
deploy/scripts/local-infra.sh status
deploy/scripts/local-infra.sh psql
```

迁移会备份旧 PostgreSQL 与 Redis 数据，把 `.env.preview` 中的数据库凭证、加密主密钥、IP 哈希盐和管理员配置迁入 `.env.local`，停止旧预览容器，将旧数据卷复制到共享中间件卷并启动共享中间件。核对达人、用户数和主要页面无误前，保留旧卷；不要对旧预览执行 `down -v`。

`deploy/nginx/preview/preview.conf` 的 `location` 与反向代理规则（含登录限流）须与生产模板 `deploy/nginx/templates/default.conf.template` 保持同步。预览 Compose 挂载整个 `nginx/preview/` 目录（而非单文件）：git 更新该文件（换 inode）后 `preview.sh up` 结尾的 `nginx -s reload` 仍能读到新配置（L-012）。生产模板变更后要同步改预览配置。上游运行时解析（`resolver` 加变量 `proxy_pass`，任务 93）已同步到预览配置；`preview.sh up` 结尾的 `nginx -s reload` 保留，对 IP 变化已不是必需。任务 26 的推广表上传已同步：上传地址由「系统配置」的 `url.upload` 运行时决定，默认空值走管理端同源 `:8890`，该路径单独放开到 300 MB。17b 的 `/admin-api/external/` 限流由 17b 合并时同步。

预览的内存限制小于生产模板，只适合本机。Redis 仍要求密码，并且 `maxmemory-policy` 为 `noeviction`。

排障：预览网络默认是 `172.30.0.0/24`，与生产模板相同。如果本机已有网络占用该网段，给并行预览设置一个未占用的 `TCRM_PREVIEW_SUBNET`；脚本会把同一网段用于预览网络和代理信任范围。

## 系统配置（任务 34）

平台管理员菜单中的「系统配置」用于修改非敏感且无需重启即可生效的值。系统配置全平台共用，存储被覆盖的项；无覆盖记录时使用下表的环境变量或代码默认值。配置变更写入操作日志。邀请邮件有效期遵循安全规范，范围为 1～72 小时。

### 可在界面修改（§3.1）

| 分组 | 配置键 | 默认值/来源 |
|---|---|---|
| 站点 | `site.name`、`mail.from` | `SparkTrack`；`TCRM_MAIL_FROM` |
| 地址 | `url.kol-portal`、`url.link`、`url.admin`、`url.upload`、`url.redirect-fallback` | 分别为 `TCRM_KOL_PORTAL_BASE_URL`、`TCRM_LINK_BASE_URL`、`TCRM_ADMIN_BASE_URL`、`TCRM_UPLOAD_BASE_URL`、`TCRM_REDIRECT_FALLBACK_URL`；上传地址默认空值 |
| 业务默认值与上限 | `copy-token.default-quota`（1）、`copy-token.default-valid-minutes`（1440）、`copy-token.max-quota`（100000）、`copy-token.max-valid-minutes`（10080）、`shop.max-per-company`（20）、`payout-account.max-per-kol`（5）、`commission.minimum-withdrawal-cny`（200）、`kol.invite-ttl-hours`（72，最大 72）、`oper-log.retention-days`（180）、`order.sync-failure.retention-days`（0，不自动清理）、`weidian.settlement.sync-interval-minutes`（60，15～1440，任务 76）、`weidian.settlement.backfill-hour`（3，0～23，北京时间，任务 76）、`product-links.max-convert-items`（500，1～5000，任务 138） | 代码默认值；操作日志保留期按安全规范至少 180 天；结算明细拉取间隔与回扫整点改动后无需重启，下一轮调度（每 5 分钟检查）即生效；复制任务每次提交商品上限改动后下一次提交即生效 |
| 限流与安全 | `login.fail.pair-limit`（5）、`login.fail.ip-limit`（20）、`login.fail.account-limit`（50）、`login.fail.window-minutes`（15）、`captcha.issue-per-minute`（120）、`kol.register.ip-per-hour`（20）、`kol.register-preview.ip-per-hour`（20）、`kol.forgot.email-limit`（3）、`kol.forgot.email-window-minutes`（10）、`kol.forgot.ip-limit`（20）、`kol.forgot.ip-window-minutes`（60）、`product-links.ip-per-minute`（120）、`product-links.convert-token-per-minute`（10）、`product-links.resolve-token-per-minute`（120）、`weidian.qps`（5） | 代码默认值；`captcha.issue-per-minute` 可由 `crm.auth.captcha.issue-per-minute` 覆盖默认值 |

地址默认值与覆盖值必须为 HTTPS host-only URL（不带路径、查询、片段或尾斜杠）；本地允许 `http://localhost[:端口]` 与 `http://127.0.0.1[:端口]`。修改管理端地址后，须同步更新微店开放平台登记的回调域名。上传子域名应设置为 Cloudflare「仅 DNS」，并确认 `TCRM_CORS_ALLOWED_ORIGINS` 包含管理端来源。

### 不在界面修改（§3.2）

- 密钥与凭证：数据库/Redis 密码、`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、邮件 API Key、首次平台管理员账号。
- 必须重启的基础设施：数据库和 Redis 地址/端口、服务端口、跨域来源、可信代理、客户端 IP 头、线程池大小/队列、定时任务时间、点击流长度、达人登录会话时长与令牌名。
- 生产开关：登录验证码开关、邮件链接写日志开关。
- 第三方接口地址：微店、Google、汇率、Resend。
- 技术常量：字段长度、加密参数、与 Nginx 和 Spring multipart 绑定的文件大小上限。

## 生产部署（模板，使用前逐项审阅）

> 从空白主机装机到上线的**完整操作顺序**（含主机初始化、AWS/Cloudflare 控制台步骤、首次发布与上线检查）见 [`RUNBOOK.md`](RUNBOOK.md)；本节只保留模板相关的要点说明。

1. 域名与证书（ADR-037 决定 3、4）：
   - Cloudflare 解析 `t.<域名>`、`kol.<域名>`、`admin.<域名>`，三者**走代理（橙云）**，SSL/TLS 模式用 Full (strict)。`t.`、`kol.` 使用 Cloudflare 源站证书：放到 `TCRM_CERT_DIR` 的 `origin.pem`、`origin.key`。
   - **另加一条 `upload.<域名>` 的 A 记录并设为「仅 DNS」（灰云，不代理）**：推广表上传（任务 26）最大 300 MB，超过 Cloudflare 免费计划约 100 MB 的请求体上限，必须绕过代理直连源站。浏览器直连该域名，**不信任 Cloudflare 源站证书**，所以 `upload.` 与 `admin.` 一样使用 Let's Encrypt 公开证书（`public.pem`、`public.key`，由 `scripts/renew-public-cert.sh` 生成，见下文「公开证书的申请与续期」）。`admin.` 将来若经实测改为灰云，不用换证书。
   - 在 Cloudflare 打开 Authenticated Origin Pulls（SSL/TLS → 源服务器），Nginx 对 `t.`、`kol.`、`admin.` 校验 Cloudflare 的客户端证书，直连源站 IP 的请求会被拒绝（细节见「Nginx 说明」）。`upload.` 灰云直连，不校验。
   - **根域名的 A 记录（`@`）也要指向同一台机器并走代理**：微店开放平台发布应用时从根域名取验证文件 `https://<域名>/weidian_open.json`（不是 `admin.<域名>`）。Nginx 的根域名 server 块只提供该文件、其余路径 404，文件放在 `TCRM_STATIC_DIR` 根目录（不在发布目录内，换版本不会丢）。根域名用 Cloudflare 源站证书（其 SAN 覆盖 `<域名>` 与 `*.<域名>`）。
2. 填写 `deploy/.env`（`TCRM_DOMAIN`、数据库、`TCRM_REDIS_PASSWORD`（生产必填）、首次平台管理员、跳转回退地址、IP 哈希盐、中转链接地址 `TCRM_LINK_BASE_URL` 等）。首次启动用这两个变量创建平台管理员；登录后在平台管理里为默认公司创建超级管理员。升级已有库时，该用户名不能与任何已有用户冲突。`TCRM_LINK_BASE_URL` 必填，应为 `https://t.<域名>`（https、不带末尾斜杠），后台据此生成 `https://t.<域名>/t/{达人编号}/{原商品编码}`；缺失或格式不对时后台启动失败。
   微店接入还要求设置 `TCRM_SECRET_KEY` 和 `TCRM_ADMIN_BASE_URL`。使用 `openssl rand -base64 32` 生成主密钥，作为 32 字节 Base64 值保存于受控的部署密钥库，并单独备份；不要写入仓库。丢失主密钥意味着已保存的凭证全部要重新录入。`TCRM_ADMIN_BASE_URL` 必须是无末尾斜杠的 HTTPS 后台地址（例如 `https://admin.example.com`）；将该域名登记为微店开放平台的回调域名。缺失或格式错误时后台启动失败。
   `TCRM_IP_HASH_SALT` 由跳转服务与后端共用，分别用于点击 IP 哈希和订单买家 ID 哈希；生产模板已向两个容器传递同一个变量。按 `.env.example` 生成并保存于受控部署环境，至少 32 字符，不设置默认值；本地启动后端也需显式注入，缺失时启动失败。
   商品源表（ADR-021）不需要新的环境变量，但要求后端能直连 `oauth2.googleapis.com` 与 `sheets.googleapis.com`（服务器部署在海外）。客户需在自己的 Google Cloud 项目中创建服务账号、启用 Google Sheets API、下载 JSON 密钥；超管在管理端「商品 → 商品源表」上传密钥（私钥加密保存，依赖 `TCRM_SECRET_KEY`），再把每张源表分享给页面上显示的服务账号邮箱（查看者）。
3. 镜像仓库与版本（ADR-037 决定 7）：镜像由 GitHub Actions 构建并推送到 GHCR，服务器不安装 Node.js、Maven，也不构建任何镜像。在 `deploy/.env` 设置 `TCRM_IMAGE_REGISTRY`（例如 `ghcr.io/ksws00684315`，小写、无末尾斜杠）。镜像为私有时，服务器用只有 `read:packages` 权限的令牌登录一次：`echo <令牌> | docker login ghcr.io -u <GitHub 用户名> --password-stdin`（令牌不写进仓库和 `.env`，凭证由 Docker 保存在登录用户的 `~/.docker/config.json`）。`TCRM_VERSION` 由发布脚本维护；**首次发布前确认它与本次要发布的版本一致**（`.env.example` 占位值是 `1.0.0`）：首次发布会把 `crm-postgres` 固定到该值并预拉取该镜像，保留占位值而首发其他版本时会去拉不存在的数据库镜像、发布在拉取步中止。
4. 创建数据卷（只做一次）：`docker volume create tcrm_pgdata && docker volume create tcrm_redisdata`。生产 Compose 把这两个卷声明为 `external`，所以 `docker compose down -v` 不会删除数据；没有先创建卷时发布会报找不到卷。先生成公开证书（下文），并把源站证书放进 `TCRM_CERT_DIR`（没有证书文件时发布脚本拒绝启动 Nginx），再把仓库切到要发布的版本标签并发布：`git fetch --tags && git checkout v1.0.0 && deploy/scripts/release.sh deploy 1.0.0`。首次发布没有 postgres 容器，脚本跳过备份，并启动 postgres、redis、crm-admin、crm-redirect 和 Nginx。随后立即按「备份与恢复」创建 pgBackRest 仓库（`stanza-create`）并做第一次全量备份。之后每次发布见「发布与回滚」。
5. 备份（ADR-037）：按下文「备份与恢复」配置 pgBackRest 与每日加密逻辑备份，上线前做一次完整恢复演练。
6. 邮件服务（达人邀请与找回密码，任务 12a）：见下节「邮件服务（Resend）」。
7. 上线检查：**生产环境禁止开启 `crm.mail.log-links`**（该开关会在日志中记录收件人与邮件链接，仅限本机开发排查使用，默认关闭）。
8. 上线检查（任务 26）：`upload.<域名>` 的 DNS 记录已创建且 Cloudflare 状态为「仅 DNS」；`public.pem` 覆盖 `upload.<域名>`（用 `openssl s_client -connect upload.<域名>:443 -servername upload.<域名> </dev/null | openssl x509 -noout -issuer -subject` 确认签发者是 Let's Encrypt，而不是 Cloudflare 源站证书）；系统配置中的 `url.upload` 已设为 `https://upload.<域名>`（默认空值上传会走管理端同源，Cloudflare 会拒绝超过约 100 MB 的请求体）；`TCRM_CORS_ALLOWED_ORIGINS` 包含 `https://admin.<域名>`（跨域上传的预检与正式请求需要）。

9. 版本一致性检查（#106）：生产模板通过 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS`（`.env` 未设置时为空，即严格模式）控制 `crm-admin`。数据库结构版本比镜像新（典型场景：发布后回滚到旧镜像，或「先发库、后发镜像」顺序颠倒）时，后台**启动即失败**，日志中 Flyway 报「applied migration not resolved locally」，随后一条 ERROR 说明「数据库结构比镜像新，请部署包含全部已应用迁移的镜像」。本机开发与预览共用同一个数据库、且各分支迁移版本不同，**不要**在 `docker-compose.dev.yml` / `docker-compose.preview.yml` 中设置该变量（默认 `*:future`，仅告警）。
   应急回滚方法：确认业务上确实要用旧镜像启动时，在 `deploy/.env` 临时加一行 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:future'`，再执行 `deploy/scripts/release.sh rollback <版本>`；此时旧镜像不认识的枚举值在列表中显示为 `—`（任务 73 容错），仅新增功能不可用。事后删除该行，并尽快用 `release.sh deploy` 发布不低于库版本的镜像（脚本重建 `crm-admin`，恢复严格模式）。发布脚本从不修改这个变量。

10. 数据库账号分离（ADR-037）：生产 PostgreSQL 的超级用户 `TCRM_DB_SUPERUSER_USERNAME` / `TCRM_DB_SUPERUSER_PASSWORD` 只用于管理和备份；后台使用 `TCRM_DB_USERNAME`，它由 `postgres/initdb/01-app-role.sh` 在**首次初始化空数据卷**时创建，没有超级用户权限，是库 `TCRM_DB_NAME` 的 owner，Flyway 用它建表。初始化脚本只在数据卷为空时执行：已有数据的卷不会补建账号，也不会改变现有账号。两个用户名必须不同，密码各自独立。
11. 首次启动并以平台管理员成功登录后，从 `deploy/.env` 删除 `TCRM_BOOTSTRAP_ADMIN_PASSWORD`（用户名可保留），再 `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --no-deps crm-admin` 让配置生效（务必带 `--no-deps`，原因见「发布与回滚」）；系统里已有平台管理员时，后台不再需要这两个变量。
12. 日志与共享内存：所有容器使用 `json-file` 日志并轮转（每个服务最多 5 个、每个 50 MB）；PostgreSQL 的 `shm_size` 为 256 MB。

### 发布与回滚

依据 ADR-037 决定 2、7。镜像由 GitHub Actions 构建一次，服务器只拉取；前端不单独起容器，由同一个 Nginx 用 `current` 软链接切换。

**前提与限制**

- **单实例发布有 30～60 秒的管理端中断**：`crm-admin` 被替换到健康为止，管理端和达人端的接口（`/admin-api/`、`/kol-api/`）返回 502；静态页面和 `t.<域名>` 跳转不受影响。**发布选在低峰期。**
- `crm-redirect` 只在镜像摘要变化时才重启；它重启时跳转短暂不可用。只改后台的版本不重启它。工作流关闭了 provenance 和 SBOM 附加物，并使用注册表构建缓存，没有改动的 `redirect/` 源码重新构建得到相同的镜像摘要。
- **标签指向的提交必须已在 `main` 上并通过 `main` 的检查**。发布工作流不重复跑测试，只检查提交是 `main` 的祖先。
- 服务器需要：Docker 与 Compose 插件、`gpg`（发布前备份用 `pg-backup.sh`，口令文件 `TCRM_BACKUP_PASSPHRASE_FILE`，权限必须是 600，见「备份与恢复」）、对 GHCR 的读取权限（`read:packages` 令牌）。`deploy/.env` 必填项发布前由脚本检查。
- Compose 文件、Nginx 配置和脚本来自服务器上的仓库，镜像里只有应用：**发布前先把仓库切到同一个标签**（`git fetch --tags && git checkout v<版本>`）。
- 同一个版本号不要重复打标签或重新推送：脚本发现 `releases/<版本>/` 已经解出就不再替换。
- **`deploy/nginx/` 下的配置变更不随发布生效**：发布脚本按设计不重启也不重载 Nginx（步骤 6），Nginx 只在启动时读取模板与片段，`git checkout` 只改宿主机上的文件。本次发布如果改了 `deploy/nginx/templates/` 或 `deploy/nginx/snippets/`（例如安全响应头），发布完成后必须手动重载：先 `docker compose -f deploy/docker-compose.prod.yml exec -T nginx nginx -t` 校验配置，再执行 `docker compose -f deploy/docker-compose.prod.yml exec -T nginx nginx -s reload`（平滑重载，不中断现有连接），最后用 `curl -I` 核对目标响应头，确认新配置已生效。回滚到带 Nginx 配置变更的版本时同样要重载一次，否则线上仍停留在较新的配置。

**发布一个版本**

1. 在 `main` 打标签并推送：`git tag v1.0.1 && git push origin v1.0.1`。`Release` 工作流构建并推送 `linux/amd64` 镜像（标签为不带 `v` 的版本号）：

   | 镜像 | 内容 |
   |---|---|
   | `<TCRM_IMAGE_REGISTRY>/crm-admin:1.0.1` | 后台 |
   | `<TCRM_IMAGE_REGISTRY>/crm-redirect:1.0.1` | 跳转服务 |
   | `<TCRM_IMAGE_REGISTRY>/crm-postgres:1.0.1` | PostgreSQL 17.11 + 写死版本的 pgBackRest（`deploy/postgres/Dockerfile`，PGDG 只保留较新的版本，所以只在发布流程里构建一次） |
   | `<TCRM_IMAGE_REGISTRY>/crm-static:1.0.1` | 两个前端的静态文件（含 `licenses/`），`FROM scratch`，不运行 |

   工作流也可以在 GitHub 页面手动运行，运行时选择标签作为 ref；不在标签上运行会失败。

2. 在服务器上：`git fetch --tags && git checkout v1.0.1 && deploy/scripts/release.sh deploy 1.0.1`。脚本依次：
   1. 检查 `.env` 必填项、`gpg` 与口令文件；记录当前版本（`.env` 的 `TCRM_VERSION`）；
   2. 拉取四个镜像，把静态镜像解出到 `$TCRM_STATIC_DIR/releases/1.0.1/`（只读取、不改动线上，所以放在备份之前：拉取失败时不产生任何变更，备份与更新之间的间隔最短）；
   3. 运行 `deploy/backup/pg-backup.sh`，**失败即中止**（首次部署没有数据库，跳过）；
   4. 更新 `crm-admin` 并等待健康。**不健康时前端不切换**，脚本报错退出；
   5. 仅当 `crm-redirect` 镜像摘要变化时更新它；
   6. 原子切换 `current`（临时链接 + `mv -T`），Nginx 不重启、不重载；本次发布改了 `deploy/nginx/` 下的配置时，发布后按「前提与限制」手动重载；
   7. 把 `TCRM_VERSION` 写回 `.env`，在 `deploy/releases.log`（已被 Git 忽略）追加记录，只保留最近 5 个静态版本（正在使用的和上一个版本不会被删）。

   任一步失败，脚本停止并打印当前状态和下一步建议，**不自动回滚数据库**。`crm-admin` 被替换后失败时，线上管理端接口不可用，按建议重新执行 `release.sh deploy <上一个版本>` 恢复。发布和回滚都不碰 PostgreSQL、Redis、Nginx 容器。
3. `deploy/scripts/release.sh status` 随时查看：当前版本、各容器的镜像与摘要、`current` 指向、最近的发布记录。

**目录布局**：`TCRM_STATIC_DIR` 下是 `releases/<版本>/admin`、`releases/<版本>/kol` 和相对软链接 `current -> releases/<版本>`。生产 Compose 挂载的是 `TCRM_STATIC_DIR` 这个**父目录**（Docker 绑定挂载在挂载时解析软链接，直接挂载 `current` 会永远停在第一个版本），Nginx 的 `root` 是 `/usr/share/nginx/sites/current/{admin,kol}`。切换时会把上一版本 `admin/assets`、`kol/assets` 里新版本没有的文件补进新版本（不覆盖同名文件），已打开页面懒加载旧 chunk 时一般不再 404；若仍异常再刷新。

**回滚**：`deploy/scripts/release.sh rollback 1.0.0` 切回该版本的镜像和静态目录（镜像或静态目录不在本机时从仓库取回）。回滚不自动备份，也不动数据库；回滚前如需备份先手动运行 `deploy/backup/pg-backup.sh`。

- 目标版本与当前版本的数据库结构兼容（没有新迁移）时，回滚直接成功。
- 当前数据库已经执行了目标版本不认识的迁移时，Flyway 严格模式（第 9 项）让旧镜像**启动即失败**。脚本检测到后打印原因和下面的应急方法，管理端接口保持不可用，**脚本不会修改 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS`，也不改数据库**。两条出路：`release.sh deploy <较新的版本>` 立刻恢复服务；或确实要用旧镜像时，按第 9 项在 `.env` 临时加 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS='*:future'`，重新执行 `release.sh rollback <版本>`，事后删除该行并发布不低于库版本的镜像。

**发布之后的 Compose 命令**：`crm-admin` / `crm-redirect` / `crm-static` 用 `${TCRM_VERSION}`；`crm-postgres` 用独立的 `${TCRM_POSTGRES_VERSION}`（未设置时回退到 `TCRM_VERSION`，兼容旧主机）。日常 `release.sh deploy` 只改应用版本，不拉、不改数据库镜像；只有显式 `deploy --postgres <版本>` 才写入并拉取新的 postgres 标签，且仍不自动重启数据库。要在发布之外重建某个服务，一律用 `up -d --no-deps <服务>`。需要升级 pgBackRest 或 PostgreSQL 补丁版本时：`release.sh deploy <当前应用版本> --postgres <新库版本>`，再在低峰期手动 `up -d --no-deps postgres`（数据库约中断十几秒）。

### 公开证书的申请与续期

`scripts/renew-public-cert.sh` 用写死版本的 `certbot/dns-cloudflare` 镜像，通过 DNS-01 为 `admin.<域名>`、`upload.<域名>` 申请同一张证书，成功后把完整证书链写为 `$TCRM_CERT_DIR/public.pem`、私钥写为 `public.key`（权限 600），有变化时执行 `nginx -t` 和 `nginx -s reload`。

1. 在 Cloudflare 创建 API 令牌，权限只给本域名所在 Zone 的「Zone → DNS → Edit」，写入仓库**之外**的文件（例如 `/etc/tcrm/cloudflare.ini`，权限 600，属主为运行脚本的用户）：`dns_cloudflare_api_token = <令牌>`。令牌不进 `.env`、不进日志；脚本发现文件权限不是 600 会拒绝运行。
2. 在 `deploy/.env` 设置 `TCRM_CERTBOT_CLOUDFLARE_INI`（上述文件的路径）和 `TCRM_CERTBOT_EMAIL`（接收到期提醒）。
3. 首次申请：在 `up` 之前以能运行 docker 的用户（通常是 root）执行 `deploy/scripts/renew-public-cert.sh`。证书状态和 Let's Encrypt 账号保存在 `deploy/certbot/`（已被 Git 忽略，和密钥一起备份）。
4. 续期：host cron 每天一次，已并入 [`RUNBOOK.md`](RUNBOOK.md) 的 root crontab 代码块（每天 04:17 UTC；certbot 距到期不足 30 天才会真正续期，其余时候脚本输出「certificate unchanged」）。续期失败时脚本非零退出，旧证书继续使用到过期，日志在 `/var/log/tcrm-cert.log`；证书剩余有效期由 `ops-check.sh` 每次运行检查（见「监控告警」）。
5. 脚本的离线测试：`bash deploy/scripts/test-renew-public-cert.sh`（用桩替换 docker，验证文件落位、权限、重载顺序和令牌不进输出）。真实申请需要真实域名和 Cloudflare 令牌，上线前在服务器上完成并记录。

### AWS 主机与防火墙（ADR-037）

- **防火墙以云安全组为准**。Docker 发布端口（`80:80`、`443:443`）通过自己的 iptables 规则转发，**不受 `ufw` / `firewalld` 的规则限制**，不要把 ufw 当作防线。
- 安全组入站只开 80、443（任意来源，`upload.` 灰云需要直连）和 SSH（限制为负责人的固定来源 IP，不要对 `0.0.0.0/0` 开放）。PostgreSQL、Redis、后台、跳转服务没有发布端口，不要为它们开放任何入站规则。
- `upload.` 直连会暴露源站 IP，安全组无法只放行 Cloudflare 回源段；`t.`、`kol.`、`admin.` 由 Nginx 的回源认证拒绝绕过 Cloudflare 的请求。
- `/etc/docker/daemon.json` 建议设置 `{"live-restore": true}`（修改后 `systemctl reload docker`）：升级或重启 Docker 守护进程时，容器继续运行。该文件属主机配置，不在仓库内。

## 备份与恢复

依据 [ADR-037](../docs/06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 5、6。恢复目标是**建议值，待客户确认**（待确认清单第 35 项）：最多丢失约 5 分钟数据，整机故障 2 小时内恢复。

### 备份层次

| 层次 | 内容 | 频率与保留 | 位置 |
|---|---|---|---|
| 1. pgBackRest | 全量 + 差异备份，加 WAL 连续归档；客户端加密（aes-256-cbc），S3 只看到密文 | WAL 持续归档（`archive_timeout=300`：有写入时最多约 5 分钟切段；**完全空闲时不会切段**）；周日全量、其余日差异；保留 4 个全量（约四周） | S3，路径 `PGBACKREST_REPO1_PATH`（默认 `/pgbackrest`） |
| 2. 逻辑备份 | `pg_dump` 自定义格式，`gpg` 对称加密（AES256），只保留 `.dump.gpg` | 每日 03:00；7 日 + 4 周（周日） | 本机 `TCRM_BACKUP_DIR`；S3 `<前缀>/daily/`、`<前缀>/weekly/` |
| 3. S3 防护 | 跨区域复制、Object Lock、生命周期规则（`daily/` 7 日、`weekly/` 28 日；**两个桶都需要非当前版本清理规则**，否则版本控制与复制下的旧版本只增不减，取值见 [`RUNBOOK.md`](RUNBOOK.md) 第 2 章） | 由负责人在 AWS 控制台配置（负责人操作清单），仓库不创建 | S3 |
| 4. 云快照 | 只用于恢复主机系统环境，**不是**数据备份 | 按需 | EC2 / Lightsail |
| Redis | 只在本机保留 AOF，**不做异地备份** | 持续 | 本机数据卷 |

两层数据库备份互为补充：pgBackRest 负责按时间点恢复，逻辑备份用于跨版本迁移、单库取回，以及 pgBackRest 仓库本身出问题时的兜底。

**Redis 不做异地备份的理由**：Redis 里是可重建的数据。链接索引由数据库重建（每天 04:30 UTC 自动全量重建，丢失后超管点「重建链接索引」立即恢复）；登录会话丢失只需重新登录；未消费的点击流是仅有的损失，量小且只影响统计。需要永久保存的业务数据都在 PostgreSQL。因此不为 Redis 增加备份和恢复流程。

**主密钥和备份口令必须与备份分开存放**。以下三项分别保存在受控的密钥库（如密码管理器），不放在主机的备份目录、S3 桶或仓库里，也不要只保存在被备份的这台主机上：`PGBACKREST_REPO1_CIPHER_PASS`（丢失则 pgBackRest 的所有备份无法读取）、`TCRM_BACKUP_PASSPHRASE_FILE` 的内容（丢失则所有 `.dump.gpg` 无法解密）、`TCRM_SECRET_KEY`（数据库里的微店等凭证用它加密，丢失则恢复后凭证全部要重新录入）。备份与密钥放在一起，加密就失去意义。

### 配置与首次启用

1. 在 `deploy/.env` 填写 `.env.example`「Backup and restore」一节：`PGBACKREST_REPO1_S3_BUCKET`、`PGBACKREST_REPO1_CIPHER_PASS`（`openssl rand -base64 32` 生成）、区域，以及 `TCRM_PGBACKREST_CREDS_DIR`（必须设在**仓库之外**，例如 `/var/lib/tcrm/pgbackrest-creds`）。可选变量要么注释掉要么删除，**不能留空**：pgBackRest 对空的 `PGBACKREST_*` 变量直接报错。密钥和口令只经环境变量传入，不写进 `command`、`pgbackrest.conf` 或镜像；`pg_stat_activity`、PostgreSQL 日志和 pgBackRest 日志里都没有口令（pgBackRest 自己的日志把它显示为 `<redacted>`）。

   **S3 凭证只有实例角色一种方式**（ADR-037 决定 1）：主机用 EC2 实例角色，不保存访问密钥。postgres 容器**读不到**实例元数据（IMDSv2 强制、hop limit 为 1，容器隔着 Docker 网桥多一跳，L-107），所以凭证由**主机**定时取好写成 pgBackRest 配置片段：`deploy/backup/refresh-s3-credentials.sh` 用 AWS CLI v2 的默认凭证链取临时凭证（必须是带过期时间的实例角色凭证，取不到长期密钥写不进文件；剩余有效期不足 30 分钟时拒绝写入并保留旧文件），写进 `TCRM_PGBACKREST_CREDS_DIR`（属主为镜像内 postgres 用户的 uid 999，权限 600，原子替换）。整个目录以只读方式挂进容器的 `/etc/pgbackrest/conf.d`（pgBackRest 默认的 `config-include-path`；挂整个目录而不是单文件，原因见 L-012）。pgBackRest 每条归档、备份、恢复命令都重新读取该目录，凭证轮换不需要重启 postgres。

   **首次启用顺序**（顺序错了归档会失败）：
   1. 建目录：`install -d -m 755 /var/lib/tcrm/pgbackrest-creds`（仓库外）。**目录必须是 755，不能收成 700**：目录属主是 root，而容器里的 pgBackRest 以 uid 999（postgres）读取凭证文件，700 会挡掉它对目录的进入权限，归档和备份全部失败；机密性由文件本身保证（600、属主 uid 999，只有 postgres 读得到）。父目录保持默认权限即可（bind 挂载的路径由 Docker 守护进程解析，容器不穿越宿主机父目录）；
   2. 跑一次刷新脚本：`TCRM_PGBACKREST_CREDS_DIR=/var/lib/tcrm/pgbackrest-creds deploy/backup/refresh-s3-credentials.sh`。该脚本只读环境变量、**不加载 `deploy/.env`**（与其他主机脚本不同），目录变量必须像这样行内传入（cron 里同理）；失败非零退出，原因在 stderr；脚本会把目录权限校正回 755；
   3. 再启动 postgres（凭证目录空着启动也能起来，但首次归档会失败）。
2. 备份写入身份的权限按 `backup/iam-policy.example.json` 收紧：只能访问备份桶的 `pgbackrest/`、`logical/` 前缀；显式拒绝修改生命周期、Object Lock、复制、版本控制和桶策略，以及删除对象版本（启用版本控制后，删除只产生删除标记，已锁定的版本删不掉）。把示例中的桶名和前缀换成实际值。
3. 启动 postgres 后创建仓库并做第一次全量备份（只做一次）：

   ```bash
   cd /opt/tcrm   # 仓库根目录
   PB="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -u postgres postgres pgbackrest --stanza=tcrm"
   $PB stanza-create
   $PB check                                  # 确认 WAL 能归档到 S3
   deploy/backup/pgbackrest-backup.sh full
   $PB info                                   # 查看备份集和 WAL 范围
   ```

4. 逻辑备份需要主机装有 `gpg` 和 AWS CLI。口令文件放在仓库之外，权限 600：`install -m 600 /dev/null /etc/tcrm/backup.pass`，再写入随机口令（`openssl rand -base64 32 > /etc/tcrm/backup.pass`），路径写进 `TCRM_BACKUP_PASSPHRASE_FILE`。脚本发现权限不是 600 会拒绝运行。
5. 定时任务：全部定时任务（凭证刷新、pgBackRest 备份、逻辑备份、证书续期、`ops-check`）统一安装在 root crontab，完整代码块见 [`RUNBOOK.md`](RUNBOOK.md)「定时任务与首次 ops-check」（含逐行注释、`PATH` 设置和凭证刷新的行内变量；凭证刷新脚本不读 `deploy/.env`）。

   凭证刷新失败时非零退出并保留旧文件（实例角色凭证有效期约 6 小时，15 分钟一次的刷新足够余量）；刷新是否按时执行由 `ops-check.sh` 检查（见「监控告警」）。备份脚本失败时也非零退出；备份是否按时产出同样由 `ops-check.sh` 检查。

### S3 不可用时

WAL 归档失败时，未归档的 WAL 留在数据卷的 `pg_wal` 里。`archive-push-queue-max=4GiB`：**不是硬上限**——只在每轮推送开始时判断，磁盘要到下一个检查点才释放，实测峰值会超出队列约 1 GiB。积压被判定超限后，pgBackRest 对更早的 WAL 直接报告成功并丢弃，保证数据库继续可写。数据盘余量按 `4 GiB + max_wal_size + 突发写入量` 预留（不要只按 4+1 GiB 估算）。异步归档要求容器里有真正的 init 进程（生产模板 postgres 服务已设 `init: true`）：pgBackRest 的后台推送进程会被托管给 1 号进程，若 1 号进程是 postmaster，它把推送失败的非零退出码当成后台进程崩溃，整个数据库会反复重启。**代价是归档出现缺口**：缺口之后的时间点无法恢复，直到下一次全量备份建立新的基线。所以 S3 恢复后要立即执行 `deploy/backup/pgbackrest-backup.sh full`，并用 `pgbackrest ... info` 确认。WAL 归档是否停止由 `ops-check.sh` 监控（查 `pg_stat_archiver`，见「监控告警」）；归档缺口本身无法自动弥补，只能靠恢复后立即重做全量。

### 恢复

**pgBackRest 时间点恢复**（整库）：

```bash
deploy/backup/restore-pitr.sh latest                          # 最新已归档点，最多丢失约 5 分钟
deploy/backup/restore-pitr.sh "2026-11-01 08:30:00"           # 指定时间点，未带时区时按 UTC
deploy/backup/restore-pitr.sh --overwrite "2026-11-01 08:30:00"  # 数据目录非空时必须显式加
```

脚本先打印目标项目、目标时间和将被覆盖的内容，输入 `yes` 才继续；数据目录非空而没有 `--overwrite` 时拒绝。随后停止 crm-admin、crm-redirect、postgres（站点不可用），用一次性容器执行 `pgbackrest restore`，启动 postgres 等待回放结束，再启动应用。结束时打印必须人工完成的事：

1. 登录超管，在「对应关系」页点击「重建链接索引」（Redis 没有被恢复）；
2. 核对钱包核对日志：`docker compose ... logs crm-admin | grep "wallet reconciliation"`，出现 `mismatched creators` 时逐个达人核对余额与台账；
3. 核对恢复点之后发生的人工操作（人工归因、提现付款、预付）。它们无法从微店补拉，要按业务记录补录；
4. 立即做一次全量备份（恢复产生新的时间线）；
5. 管理员和达人需要重新登录。

主机崩溃后数据卷还在、但 PostgreSQL 没有正常关闭时，数据目录里会残留 `postmaster.pid`，pgBackRest 会拒绝恢复（提示 `unable to restore while PostgreSQL is running`）。确认 postgres 容器已停止后，手动删除这个文件再重新执行脚本。

整机丢失时：按「生产部署」在新主机上准备 `.env`（同样的 `TCRM_SECRET_KEY`、`PGBACKREST_*`）和一个**空**数据卷，启动 Compose 之前不要初始化数据库；执行 `restore-pitr.sh latest`（空数据卷不需要 `--overwrite`），其余同上。

**逻辑备份恢复**（取回某个 `.dump.gpg`；恢复到空库，用管理超级用户）：

写入身份的 IAM（`backup/iam-policy.example.json`）对 `logical/` **只写不读**，所以上面的 `aws s3 cp` **不能**用备份写入角色，否则会被拒。取回请用单独的只读身份（示例策略见同目录 `iam-policy-logical-read.example.json`）。本机已有的文件在 `TCRM_BACKUP_DIR`，无需 S3。

```bash
# 从 S3 取回（使用只读身份 / 配置文件，不要用写入备份的角色）
aws s3 cp --region us-west-2 s3://<桶>/<前缀>/daily/tcrm-<时间>.dump.gpg .
# 解密后直接送入 pg_restore，明文只在管道里
gpg --batch --quiet --pinentry-mode loopback --passphrase-file /etc/tcrm/backup.pass \
    --decrypt tcrm-<时间>.dump.gpg \
  | docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -T postgres \
      sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists'
```

### 恢复演练（上线前一次，此后每季度一次）

在**另一台**主机或独立的 Compose 项目上做，不要动生产数据卷：

1. 准备与生产相同的 `.env`（`PGBACKREST_*`、`TCRM_SECRET_KEY`），数据卷为空。
2. 执行 `deploy/backup/restore-pitr.sh latest`，再试一次指定时间点；记录从开始到后台可登录的总耗时（对照 2 小时目标）和实际丢失的数据量（对照约 5 分钟目标）。
3. 另取最近一个 `.dump.gpg` 走一遍逻辑备份恢复，确认解密口令有效。
4. 核对项，与生产对照（SQL 在恢复出的库里执行）：
   - 达人：`select count(*) from crm_kol;`
   - 订单：`select count(*), max(create_time) from crm_order;`
   - 钱包余额：`select count(*), sum(balance_cny) from crm_commission_wallet;`
   - 提现记录：`select count(*), max(create_time) from crm_commission_withdrawal;`
   - 后台能启动，Flyway 严格模式校验通过（日志出现 `Successfully validated ... migrations` 和 `Schema "public" is up to date`）；
   - 登录超管，重建链接索引，抽查一条推广链接能跳转；钱包核对日志没有 `mismatched creators`。
5. 把耗时、丢失窗口、核对结果记入演练记录；S3 连通、跨区域复制和 Object Lock 的实际效果也在这次演练里确认。

## 监控告警（任务 96，ADR-037 决定 8）

不部署 Prometheus、Grafana。监控由两层组成：外部探测（第三方拨测服务）发现「外部看起来不可用」，主机检查脚本（`deploy/scripts/ops-check.sh`）发现「主机内部在劣化」。仓库不创建任何真实监控服务，本节的账号、机器人、令牌都由负责人在上线前创建（负责人操作清单）。

### 主机检查脚本（ops-check.sh）

host cron 每 5 分钟运行一次，与备份脚本装在同一个 root crontab 里：cron 行见 [`RUNBOOK.md`](RUNBOOK.md)「定时任务与首次 ops-check」的整块 crontab。

| 检查 | 告警条件 | 默认阈值 |
|---|---|---|
| 磁盘 | 本地文件系统使用率过高 | ≥ 70% 告警，≥ 85% 标记严重 |
| 内存 | 可用内存不足 | < 10% |
| 容器 | 五个 Compose 服务（nginx、crm-admin、crm-redirect、postgres、redis）任一非 running、unhealthy，或 5 分钟内重启过 | 窗口 5 分钟 |
| 公开证书 | `TCRM_CERT_DIR/public.pem`（`admin.`、`upload.` 的 Let's Encrypt 证书）剩余有效期 | ≤ 20 天 |
| pgBackRest | 最新备份过旧；或存在待归档 `.ready` 段且归档无进展（`pg_ls_archive_statusdir` + `pg_stat_archiver`） | 备份 > 26 小时；有 `.ready` 且无进展 > 15 分钟（库空闲、无待归档段时不告警） |
| 备份凭证 | pgBackRest 的 S3 凭证片段（`TCRM_PGBACKREST_CREDS_DIR/s3-credentials.conf`，任务 106）缺失或长时间未刷新；`TCRM_PGBACKREST_CREDS_DIR` 未设置或 `PGBACKREST_REPO1_TYPE=posix` 时不检查（posix 仓库没有凭证文件，而 Compose 仍要求设置该目录变量） | > 60 分钟未刷新或文件缺失 |
| 逻辑备份 | 最新 `.dump.gpg` 过旧（`TCRM_BACKUP_DIR`） | > 26 小时 |
| 应用日志 | 上次检查以来 crm-admin、crm-redirect 出现 ERROR | — |
| 容量告警 | 日志里出现 Redis 内存（`Redis used_memory`）或点击积压（`Click stream backlog`）的 WARN（后台每分钟检查，见「资源与 Redis 容量」） | — |

行为约定：

- **告警只发到 Webhook，日志内容不出主机**：告警文本只含级别、logger 名、次数和 traceId，不含消息正文，个人信息不会流向第三方。Webhook 是飞书自定义机器人格式（`{"msg_type":"text",…}`），`TCRM_ALERT_WEBHOOK_URL` 为空时只输出到标准输出。
- **飞书机器人配置**：自定义机器人不要开签名校验；若启用关键词，至少放行 `ALERT` 与 `RECOVERED`（脚本用这两个前缀）。飞书在关键词/签名失败时仍返回 HTTP 200 且 `code≠0`，脚本会检查响应体里的 `"code":0`，失败写 stderr，不把这次当成送达。
- **去重与恢复**：同一告警**签名不变**时最多每小时重复一次；签名取「告警文本去掉数字」，因此 72%→73% 不重发，但 warn→CRITICAL、同类里多出一个故障服务会立即重发。恢复时发一条 `RECOVERED` 通知。状态文件在 `TCRM_OPS_STATE_DIR`（默认 `deploy/ops-state/`，不入库）。
- **心跳**：每次运行结束请求一次 `TCRM_HEARTBEAT_URL`（Healthchecks.io 风格）；脚本或主机挂了、ping 停了，由该外部服务告警——这是脚本自身故障的唯一发现手段。
- 阈值可用 `TCRM_OPS_*` 环境变量覆盖（见 `.env.example`），只为测试设计，生产保持默认。
- 全新数据卷首次启动时 postgres 可能退出一次再自动重启（遗留 L-061）：表现为一条「5 分钟内重启过」的告警和随后的恢复通知，不是持续故障；持续出现才需要排查。
- **发版重启**：每次 `release.sh` 会重启容器，下一轮检查通常会发一对「5 分钟内重启过」告警和随后的恢复通知，属预期，不必当故障排查。
- 磁盘检查会排除 `/dev/loop*` 与 `/snap/`（Ubuntu snap 只读挂载常年 100%，否则会持续 CRITICAL）。

### 外部探测（负责人创建）

用任意拨测服务（如 UptimeRobot）对以下地址建探测，全部走公网域名、间隔 5 分钟、失败即通知：

| 探测 | 地址 | 判定 |
|---|---|---|
| 跳转服务 | `https://t.<域名>/healthz` | HTTP 200 |
| 管理端 | `https://admin.<域名>` | HTTP 200/302（登录页或跳转） |
| 达人端 | `https://kol.<域名>` | HTTP 200 |
| 心跳 | Healthchecks.io 类服务分配的 URL，填入 `TCRM_HEARTBEAT_URL` | 未按期 ping 时告警 |

`upload.<域名>` 不建拨测：300 MB 上传不适合探测请求，证书到期已由主机检查覆盖。

### 告警处理指引

| 告警 | 先做什么 |
|---|---|
| disk | 清理日志与镜像（`docker system df` 定位）；≥ 85% 会很快写满 WAL 和备份，立即处理 |
| memory | `free -m`、`docker stats` 找出占用者；持续低于 10% 说明 4 核 8G 规划到顶，按「资源与 Redis 容量」评估扩容 |
| container | `docker compose ps`、`docker compose logs <服务>`；crm-admin 反复重启先看是否 Flyway 严格模式拒绝（见「发布与回滚」第 9 项） |
| cert | 重跑 `deploy/scripts/renew-public-cert.sh`，失败原因看 `/var/log/tcrm-cert.log`；连续失败检查 Cloudflare API 令牌 |
| pgbackrest | 备份过旧：手动 `deploy/backup/pgbackrest-backup.sh`；WAL stalled（有 `.ready` 且无进展）：S3 连通性与凭证，按「S3 不可用时」处理，恢复后立即做全量；库完全空闲且无 `.ready` 时不会告警 |
| backup | 手动 `deploy/backup/pg-backup.sh` 看报错（gpg、口令文件权限、磁盘） |
| applog / capacity | 登录管理端按 traceId 查操作日志；Redis 内存或点击积压 WARN 按上表阈值安排扩容，不要等 ERROR |

## 邮件服务（Resend）

达人邀请与重置密码邮件经 Resend（`https://resend.com`）发送（设计说明书 §7.3）。免费额度 3,000 封/月、100 封/天，合同规模远低于上限。相关环境变量（见 `.env.example`）：

| 变量 | 说明 |
|---|---|
| `TCRM_MAIL_API_KEY` | Resend API Key；**为空时使用日志兜底**：邮件只写后台日志、不真正发出（仅限本机开发） |
| `TCRM_MAIL_FROM` | 系统配置 `mail.from` 的默认发件人，例如 `SparkTrack <no-reply@你的域名>`；配置了 Key 却未在默认值或系统配置中提供发件人时后台启动失败 |
| `TCRM_KOL_PORTAL_BASE_URL` | 必填，无末尾斜杠的 HTTPS 达人端地址（生产为 `https://kol.<域名>`）；缺失或格式不对时后台启动失败。邀请邮件链接 `{该地址}/activate?token=…`，重置邮件链接 `{该地址}/reset?token=…` |

配置步骤：

1. 注册 Resend 并在控制台 **Domains → Add Domain** 添加发件域名（用主域名或子域如 `mail.你的域名`），按控制台给出的记录值在域名 DNS 中添加：
   - **SPF**：一条 TXT 记录（如 `resend._domainkey` 之外的控制台指定的 `v=spf1 include:…`）；
   - **DKIM**：控制台给出的 TXT 记录（`resend._domainkey.你的域名.`，值为 `p=` 开头的公钥）；
   - **DMARC**：`_dmarc.你的域名.` TXT 记录，值建议 `v=DMARC1; p=quarantine; rua=mailto:你的邮箱`（先 `p=none` 观察再收紧）。
   在 Resend 控制台点 Verify 确认全部通过（DNS 生效可能需要几分钟到数小时）。
2. **API Keys → Create API Key**，权限 Full Access 即可，把 `re_` 开头的值填入 `.env` 的 `TCRM_MAIL_API_KEY`；`TCRM_MAIL_FROM` 填 `展示名 <no-reply@你的发件域名>`（域名必须是上一步验证过的）。
3. 用后台「新建达人」触发一封邀请邮件到你的邮箱，确认收到、链接可打开；再走一遍「忘记密码」确认重置邮件。
4. 发送失败只记 `WARN` 不回滚（可用重发邀请补发）；不要把 API Key 写入仓库或日志。`crm.mail.log-links`（默认 false）只在本机开发时用于把邮件里的链接打到日志里排查，**生产禁止开启**。

## 资源与 Redis 容量

资源限制按设计说明书 §10：PostgreSQL 2G（`shared_buffers=512MB`）、Redis 1.5G（`maxmemory 1280mb`、`noeviction`）、crm-admin 2G、crm-redirect 512M。只有 nginx 映射 80/443；PostgreSQL、Redis、后台、跳转服务只在 compose 网络内可达。

**链接索引与 Redis 容量**（设计说明书 §7.4）：`maxmemory 1280mb` 约可容纳 700 万个链接 field（约 350 位达人 × 2 万）；合同规模 100 位达人（200 万个 field，约 328MB）远低于上限，超过约 350 位达人时需把 Redis 上限调到 2GB 以上并相应增加服务器内存。后台每分钟检查一次 Redis 内存，超过上限 70% 记 `WARN`、85% 记 `ERROR`。**Redis 同时保存登录会话、登录失败计数、链接索引和点击流，并配置为内存满时拒绝写入（`noeviction`）。管理端每个请求都会写 Redis 续期会话（2 小时空闲超时），内存写满后管理端所有请求都会失败、管理员无法登录，新点击也会丢失。出现内存 `WARN` 就应安排扩容，不要等到 `ERROR`。**链接索引每天 04:30（UTC）自动全量重建；Redis 数据丢失或换实例后，请超管在管理端"对应关系"页点击"重建链接索引"立即恢复，否则在下一次定时重建前，中转链接会跳到回退地址。

## Nginx 说明

- `t.<域名>` 只转发 `/t/…`、`/i/…`（客户表格脚本写入的按复制商品 ID 中转链接，ADR-019）与 `/healthz` 到 crm-redirect，其余返回 404。`/t/` 与 `/i/` 共用同一限速区，按客户端 IP 限速 30 次/秒、突发 60，超出返回 429；`/healthz` 不限速。阈值偏松，是为了不误伤移动网络中大量用户共用出口 IP（CGNAT）的情况。边缘层（Cloudflare 频率限制规则）是主要防线，Nginx 限速是源站兜底；阈值可按实际流量调整。
- `kol.<域名>` / `admin.<域名>`：静态资源 + 单页应用回退；`/kol-api/`、`/admin-api/` 转发到 crm-admin；登录接口有 10 次/分钟（突发 5）的频率限制；登录验证码签发接口 `/admin-api/auth/captchas` 有 120 次/分钟（突发 30）的频率限制，后台另按每 IP 每分钟 120 张限制（`crm.auth.captcha.issue-per-minute`，ADR-017）。
- **证书（ADR-037）**：`t.`、`kol.` 用 `snippets/tls.conf`（Cloudflare 源站证书 `origin.pem` / `origin.key`），`admin.`、`upload.` 用 `snippets/tls-public.conf`（Let's Encrypt `public.pem` / `public.key`）；协议与会话参数共用 `snippets/tls-common.conf`。
- **回源认证（Authenticated Origin Pulls，ADR-037 决定 4）**：`t.`、`kol.`、`admin.` 三个 server 引用 `snippets/cloudflare-origin-pull.conf`（`ssl_client_certificate` + `ssl_verify_client on`），没有 Cloudflare 客户端证书的请求在 TLS 握手后被拒绝（返回 400）；`upload.` 不引用。CA 文件 `snippets/cloudflare-origin-pull-ca.pem` 是 Cloudflare 公布的公开证书，来源和下载日期写在文件头，Cloudflare 更换 CA 时更新。这只能证明请求来自 Cloudflare（共享证书，不证明来自本账号）。**若 `admin.` 经实测改为灰云，删除 `admin.` server 里对 `cloudflare-origin-pull.conf` 的这一行引用**；不设开关。未知主机名由默认 server 以 `ssl_reject_handshake` 拒绝，不受影响。
- **上游运行时解析（ADR-037）**：模板声明 `resolver 127.0.0.11 valid=10s ipv6=off;`（Docker 内置 DNS），每个 server 用 `set $crm_admin_upstream http://crm-admin:8080;`（跳转站点为 `$crm_redirect_upstream`）保存上游，所有 `proxy_pass` 使用该变量且不带 URI，原请求路径原样转发。效果：crm-admin 重建、IP 变化后最多 10 秒自动恢复，不用 reload；crm-admin 没在运行时 Nginx 仍能启动，`t.` 跳转照常，管理端接口返回 502。新增反代 location 时同样使用变量。
- **推广表上传子域名（任务 26，ADR-024；负责人 2026-10-18 决定）**：上传走独立的 `upload.<域名>`，在 Cloudflare 中设为「仅 DNS」不代理（免费计划的代理会拒绝约 100 MB 以上的请求体）。该 server 使用公开证书（浏览器不信任 Cloudflare 源站证书），不要求 Cloudflare 客户端证书；它只放行 `POST /admin-api/kol/kols/{id}/promo-sheet-uploads`（含浏览器先发的 CORS 预检 OPTIONS），`client_max_body_size 300m`、`proxy_read_timeout 300s`；其它路径一律 404。`admin.<域名>` 保持 `client_max_body_size 21m` 默认（导入 20 MB + 表单开销），不再为上传放宽。后端侧 Spring multipart 上限同为 300 MB（`file-size-threshold` 极小，先落临时文件），各接口仍在代码层校验各自业务上限：Excel 导入 20 MB、业务图片 2 MB、推广表 300 MB。管理端从系统配置读取 `url.upload` 并在运行时选择上传域名；默认空值回退当前域名。跨域上传要求 `TCRM_CORS_ALLOWED_ORIGINS` 包含管理端源。
- 响应头（规范 06）：`X-Content-Type-Options`、`Referrer-Policy`、HSTS（一年）、`X-Frame-Options`，两个前端另加 `Content-Security-Policy`。
- 客户端 IP：本模板用 `proxy.conf` 把转发给应用的 `X-Forwarded-For`、`CF-Connecting-IP`、`X-Real-IP` **一律重写为 `$remote_addr`**，客户端自带的转发头到不了应用（这是防伪造的实际手段）。`cloudflare-real-ip.conf` 的 `set_real_ip_from` 只决定对哪些对端采用 `CF-Connecting-IP` 来解析 `$remote_addr`，**并不限制谁可以连上 Nginx**。compose 网络固定 `172.30.0.0/24`，生产中 `crm-admin` / `crm-redirect` 的 `TCRM_TRUSTED_PROXIES` 已写死为该网段。
- `${TCRM_DOMAIN}` 由 nginx 镜像在启动时替换（`NGINX_ENVSUBST_FILTER=^TCRM_`）。

## Cloudflare 频率限制规则（需人工在 Cloudflare 控制台配置）

> **状态：未应用。** 以下是文档化的配置，仓库无法替你下发；上线前由负责人在 Cloudflare 控制台（Security → WAF → Rate limiting rules）手工创建，创建后在此处注明日期。

| 规则 | 匹配表达式 | 计数依据 | 阈值 | 动作 |
|---|---|---|---|---|
| 管理端登录验证码签发 | `http.host eq "admin.<域名>" and http.request.uri.path eq "/admin-api/auth/captchas" and http.request.method eq "POST"` | IP | 1 分钟内 120 次 | Block 1 分钟（返回 429） |

说明：阈值与后台（每 IP 每分钟 120 张）、Nginx `tcrm_captcha` 一致；Cloudflare 是第一道防线，Nginx 与后台是源站兜底。免费套餐的频率限制规则数量与可选时间窗口有限，若控制台不支持 1 分钟窗口，用最接近的窗口并按比例换算阈值。

## 可信代理（ADR-009）

生产 compose 已为 `crm-admin`、`crm-redirect` **写死** `TCRM_TRUSTED_PROXIES=172.30.0.0/24`（与 compose 网络网段一致，不读 `.env` 里的同名变量）；应用因此只信任来自 Nginx 的转发头。`TCRM_CLIENT_IP_HEADERS`、`TCRM_CLICK_STREAM_MAXLEN` 会从 `.env` 传入容器（`TCRM_CLICK_STREAM_MAXLEN` 同时传给 crm-admin，用于点击积压告警：超过 50% 记 `WARN`、80% 记 `ERROR`）；空的 headers 在应用内回退到默认值 `CF-Connecting-IP,X-Forwarded-For`。

**防伪造怎么做的**：本模板在 `proxy.conf` 里用 `$remote_addr` 覆盖转发给应用的转发头，所以客户端自带的 `CF-Connecting-IP` / `X-Forwarded-For` / `X-Real-IP` 不会到达应用。`set_real_ip_from`（`cloudflare-real-ip.conf`）只影响 Nginx 如何解析 `$remote_addr`，**不会**挡住非 Cloudflare 来源的连接。

**部署建议**：若要真正只接受 Cloudflare 回源，请在防火墙或安全组上限制 80/443 的来源 IP（Cloudflare 公布的回源段），并随 Cloudflare 公告更新；这超出本模板的能力范围。若 Nginx 对任意来源照单全收客户端自带的转发头且不重写，ADR-009「不可信对端不读转发头」的前提不成立，点击去重与登录限流可被伪造。

## 第三方许可声明

两个 Dockerfile 把 backend、redirect、admin-ui、kol-portal 四份 `THIRD-PARTY-NOTICES` 复制到镜像的 `/licenses/`；`build-static.sh` 把同样四份放进两个静态目录的 `licenses/`（`https://admin.<域名>/licenses/backend.txt` 等）。


## 客户表格脚本切换到本系统（任务 17b）

1. 在 Google 表格脚本属性中把 `TALENT_CRM_API_BASE_URL` 改为 `https://admin.<域名>/admin-api`（不要末尾斜杠）。
2. 脚本代码不用改；令牌在管理端「商品 > 复制令牌」生成。
3. Nginx 对 `/admin-api/external/` 单独限流（每 IP 每分钟 120 次，burst 40），写在 `deploy/nginx/templates/default.conf.template`（zone `tcrm_external`，upstream `crm-admin`），与应用内 Redis 限流叠加。README 只说明作用，不另贴与生产不一致的片段。
