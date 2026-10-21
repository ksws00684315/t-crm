# 部署模板（deploy）

| 文件 | 用途 |
|---|---|
| `.env.example` | 全部环境变量（名称与 backend / redirect 实际使用的一致）；复制为 `deploy/.env` 后填写，`.env` 不入库 |
| `docker-compose.dev.yml` | 本地共享中间件：PostgreSQL 17.11、Redis 7.4.5 |
| `docker-compose.prod.yml` | 生产**模板**：nginx、crm-admin、crm-redirect、postgres、redis |
| `docker-compose.preview.yml` | 本地预览：与生产相同的五个服务，只绑定 `127.0.0.1:8890/8891/8892` |
| `nginx/` | 三个站点的配置模板、安全响应头、Cloudflare 回源 IP 段；`nginx/preview/preview.conf` 是本地预览（仅 HTTP） |
| `scripts/preview.sh` | 本地预览：`up` / `down` / `reset` |
| `scripts/local-infra.sh` | 本地共享中间件的启动、停止、备份、恢复与维护命令 |
| `backup/pg-backup.sh` | 每日 `pg_dump` 备份脚本模板（保留 7 日 + 4 周，可选上传 R2） |
| `scripts/build-static.sh` | 构建两个前端并生成 Nginx 静态目录（含四份第三方许可声明） |
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

Vite 开发服务器仍按 `admin-ui/`、`kol-portal/` 各自原有命令启动。Flyway 本地配置允许忽略其他分支产生但当前代码没有的迁移，并按顺序补跑较早版本迁移。这只能缓解版本先后和缺失问题；删除的列或改掉的约束不会自动恢复，需要用 `backup` / `restore` 处理。

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

`deploy/nginx/preview/preview.conf` 的 `location` 与反向代理规则（含登录限流）须与生产模板 `deploy/nginx/templates/default.conf.template` 保持同步。生产模板变更后要同步改预览配置。任务 26 的推广表上传已同步：上传地址由「系统配置」的 `url.upload` 运行时决定，默认空值走管理端同源 `:8890`，该路径单独放开到 300 MB。17b 的 `/admin-api/external/` 限流由 17b 合并时同步。

预览的内存限制小于生产模板，只适合本机。Redis 仍要求密码，并且 `maxmemory-policy` 为 `noeviction`。

排障：预览网络默认是 `172.30.0.0/24`，与生产模板相同。如果本机已有网络占用该网段，给并行预览设置一个未占用的 `TCRM_PREVIEW_SUBNET`；脚本会把同一网段用于预览网络和代理信任范围。

## 系统配置（任务 34）

平台管理员菜单中的「系统配置」用于修改非敏感且无需重启即可生效的值。系统配置全平台共用，存储被覆盖的项；无覆盖记录时使用下表的环境变量或代码默认值。配置变更写入操作日志。邀请邮件有效期遵循安全规范，范围为 1～72 小时。

### 可在界面修改（§3.1）

| 分组 | 配置键 | 默认值/来源 |
|---|---|---|
| 站点 | `site.name`、`mail.from` | `SparkTrack`；`TCRM_MAIL_FROM` |
| 地址 | `url.kol-portal`、`url.link`、`url.admin`、`url.upload`、`url.redirect-fallback` | 分别为 `TCRM_KOL_PORTAL_BASE_URL`、`TCRM_LINK_BASE_URL`、`TCRM_ADMIN_BASE_URL`、`TCRM_UPLOAD_BASE_URL`、`TCRM_REDIRECT_FALLBACK_URL`；上传地址默认空值 |
| 业务默认值与上限 | `copy-token.default-quota`（1）、`copy-token.default-valid-minutes`（1440）、`copy-token.max-quota`（100000）、`copy-token.max-valid-minutes`（10080）、`shop.max-per-company`（3）、`payout-account.max-per-kol`（5）、`kol.invite-ttl-hours`（72，最大 72）、`oper-log.retention-days`（180） | 代码默认值；操作日志保留期按安全规范至少 180 天 |
| 限流与安全 | `login.fail.pair-limit`（5）、`login.fail.ip-limit`（20）、`login.fail.account-limit`（50）、`login.fail.window-minutes`（15）、`captcha.issue-per-minute`（120）、`kol.register.ip-per-hour`（20）、`kol.register-preview.ip-per-hour`（20）、`kol.forgot.email-limit`（3）、`kol.forgot.email-window-minutes`（10）、`kol.forgot.ip-limit`（20）、`kol.forgot.ip-window-minutes`（60）、`product-links.ip-per-minute`（120）、`product-links.convert-token-per-minute`（10）、`product-links.resolve-token-per-minute`（120）、`weidian.qps`（5） | 代码默认值；`captcha.issue-per-minute` 可由 `crm.auth.captcha.issue-per-minute` 覆盖默认值 |

地址默认值与覆盖值必须为 HTTPS host-only URL（不带路径、查询、片段或尾斜杠）；本地允许 `http://localhost[:端口]` 与 `http://127.0.0.1[:端口]`。修改管理端地址后，须同步更新微店开放平台登记的回调域名。上传子域名应设置为 Cloudflare「仅 DNS」，并确认 `TCRM_CORS_ALLOWED_ORIGINS` 包含管理端来源。

### 不在界面修改（§3.2）

- 密钥与凭证：数据库/Redis 密码、`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、邮件 API Key、首次平台管理员账号。
- 必须重启的基础设施：数据库和 Redis 地址/端口、服务端口、跨域来源、可信代理、客户端 IP 头、线程池大小/队列、定时任务时间、点击流长度、达人登录会话时长与令牌名。
- 生产开关：登录验证码开关、邮件链接写日志开关。
- 第三方接口地址：微店、Google、汇率、Resend。
- 技术常量：字段长度、加密参数、与 Nginx 和 Spring multipart 绑定的文件大小上限。

## 生产部署（模板，使用前逐项审阅）

1. 域名：Cloudflare 解析 `t.<域名>`、`kol.<域名>`、`admin.<域名>` 到服务器；SSL/TLS 模式用 Full (strict)，把 Cloudflare 源站证书放到 `TCRM_CERT_DIR`（`origin.pem`、`origin.key`）。**另加一条 `upload.<域名>` 的 A 记录并设为「仅 DNS」（灰云，不代理）**：推广表上传（任务 26）最大 300 MB，超过 Cloudflare 免费计划约 100 MB 的请求体上限，必须绕过代理直连源站；源站证书需覆盖 `upload.<域名>`（SAN 里加一条，或单独签发）。
2. 填写 `deploy/.env`（`TCRM_DOMAIN`、数据库、`TCRM_REDIS_PASSWORD`（生产必填）、首次平台管理员、跳转回退地址、IP 哈希盐、中转链接地址 `TCRM_LINK_BASE_URL` 等）。首次启动用这两个变量创建平台管理员；登录后在平台管理里为默认公司创建超级管理员。升级已有库时，该用户名不能与任何已有用户冲突。`TCRM_LINK_BASE_URL` 必填，应为 `https://t.<域名>`（https、不带末尾斜杠），后台据此生成 `https://t.<域名>/t/{达人编号}/{原商品编码}`；缺失或格式不对时后台启动失败。
   微店接入还要求设置 `TCRM_SECRET_KEY` 和 `TCRM_ADMIN_BASE_URL`。使用 `openssl rand -base64 32` 生成主密钥，作为 32 字节 Base64 值保存于受控的部署密钥库，并单独备份；不要写入仓库。丢失主密钥意味着已保存的凭证全部要重新录入。`TCRM_ADMIN_BASE_URL` 必须是无末尾斜杠的 HTTPS 后台地址（例如 `https://admin.example.com`）；将该域名登记为微店开放平台的回调域名。缺失或格式错误时后台启动失败。
   `TCRM_IP_HASH_SALT` 由跳转服务与后端共用，分别用于点击 IP 哈希和订单买家 ID 哈希；生产模板已向两个容器传递同一个变量。按 `.env.example` 生成并保存于受控部署环境，至少 32 字符，不设置默认值；本地启动后端也需显式注入，缺失时启动失败。
   商品源表（ADR-021）不需要新的环境变量，但要求后端能直连 `oauth2.googleapis.com` 与 `sheets.googleapis.com`（服务器部署在海外）。客户需在自己的 Google Cloud 项目中创建服务账号、启用 Google Sheets API、下载 JSON 密钥；超管在管理端「商品 → 商品源表」上传密钥（私钥加密保存，依赖 `TCRM_SECRET_KEY`），再把每张源表分享给页面上显示的服务账号邮箱（查看者）。
3. 构建前端静态文件：`deploy/scripts/build-static.sh`（需要 Node.js 与 pnpm），输出到 `TCRM_STATIC_DIR`（默认 `deploy/www`）。
4. 启动：`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`。
5. 备份：用 cron 每天 03:00 执行 `deploy/backup/pg-backup.sh`；上线前做一次恢复演练（命令见脚本头部）。
6. 邮件服务（达人邀请与找回密码，任务 12a）：见下节「邮件服务（Resend）」。
7. 上线检查：**生产环境禁止开启 `crm.mail.log-links`**（该开关会在日志中记录收件人与邮件链接，仅限本机开发排查使用，默认关闭）。
8. 上线检查（任务 26）：`upload.<域名>` 的 DNS 记录已创建且 Cloudflare 状态为「仅 DNS」；`TCRM_CERT_DIR` 的证书覆盖该子域名；系统配置中的 `url.upload` 已设为 `https://upload.<域名>`（默认空值上传会走管理端同源，Cloudflare 会拒绝超过约 100 MB 的请求体）；`TCRM_CORS_ALLOWED_ORIGINS` 包含 `https://admin.<域名>`（跨域上传的预检与正式请求需要）。

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
- **推广表上传子域名（任务 26，ADR-024；负责人 2026-10-18 决定）**：上传走独立的 `upload.<域名>`，在 Cloudflare 中设为「仅 DNS」不代理（免费计划的代理会拒绝约 100 MB 以上的请求体）。该 server 只放行 `POST /admin-api/kol/kols/{id}/promo-sheet-uploads`（含浏览器先发的 CORS 预检 OPTIONS），`client_max_body_size 300m`、`proxy_read_timeout 300s`；其它路径一律 404。`admin.<域名>` 保持 `client_max_body_size 21m` 默认（导入 20 MB + 表单开销），不再为上传放宽。后端侧 Spring multipart 上限同为 300 MB（`file-size-threshold` 极小，先落临时文件），各接口仍在代码层校验各自业务上限：Excel 导入 20 MB、业务图片 2 MB、推广表 300 MB。管理端从系统配置读取 `url.upload` 并在运行时选择上传域名；默认空值回退当前域名。跨域上传要求 `TCRM_CORS_ALLOWED_ORIGINS` 包含管理端源。
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
