# 部署模板（deploy）

| 文件 | 用途 |
|---|---|
| `.env.example` | 全部环境变量（名称与 backend / redirect 实际使用的一致）；复制为 `deploy/.env` 后填写，`.env` 不入库 |
| `docker-compose.dev.yml` | 本地开发依赖：PostgreSQL 17.11、Redis 7.4.5 |
| `docker-compose.prod.yml` | 生产**模板**：nginx、crm-admin、crm-redirect、postgres、redis |
| `nginx/` | 三个站点的配置模板、安全响应头、Cloudflare 回源 IP 段 |
| `backup/pg-backup.sh` | 每日 `pg_dump` 备份脚本模板（保留 7 日 + 4 周，可选上传 R2） |
| `scripts/build-static.sh` | 构建两个前端并生成 Nginx 静态目录（含四份第三方许可声明） |
| `../backend/Dockerfile`、`../redirect/Dockerfile` | 多阶段构建；运行镜像为 JRE 21，非 root 用户；构建上下文是仓库根目录 |

## 本地开发

```bash
cp deploy/.env.example deploy/.env          # 填写 TCRM_DB_USERNAME / TCRM_DB_PASSWORD 等
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.dev.yml ps    # postgres、redis 应为 healthy
```

宿主机端口由 `TCRM_DB_PORT`（默认 5432）、`TCRM_REDIS_PORT`（默认 6379）决定，只绑定 `127.0.0.1`；本机已有 PostgreSQL / Redis 占用端口时，在 `deploy/.env` 中改这两个变量即可，在本机启动的后台和跳转服务读取同名变量连接。库 `tcrm` 与应用账号由 PostgreSQL 镜像根据 `TCRM_DB_NAME`、`TCRM_DB_USERNAME`、`TCRM_DB_PASSWORD` 创建；表由后台启动时的 Flyway 创建，这里不挂载初始化 SQL。

## 生产部署（模板，使用前逐项审阅）

1. 域名：Cloudflare 解析 `t.<域名>`、`kol.<域名>`、`admin.<域名>` 到服务器；SSL/TLS 模式用 Full (strict)，把 Cloudflare 源站证书放到 `TCRM_CERT_DIR`（`origin.pem`、`origin.key`）。
2. 填写 `deploy/.env`（`TCRM_DOMAIN`、数据库、`TCRM_REDIS_PASSWORD`（生产必填）、首次平台管理员、跳转回退地址、IP 哈希盐、中转链接地址 `TCRM_LINK_BASE_URL` 等）。首次启动用这两个变量创建平台管理员；登录后在平台管理里为默认公司创建超级管理员。升级已有库时，该用户名不能与任何已有用户冲突。`TCRM_LINK_BASE_URL` 必填，应为 `https://t.<域名>`（https、不带末尾斜杠），后台据此生成 `https://t.<域名>/t/{达人编号}/{原商品编码}`；缺失或格式不对时后台启动失败。
   微店接入还要求设置 `TCRM_SECRET_KEY` 和 `TCRM_ADMIN_BASE_URL`。使用 `openssl rand -base64 32` 生成主密钥，作为 32 字节 Base64 值保存于受控的部署密钥库，并单独备份；不要写入仓库。丢失主密钥意味着已保存的凭证全部要重新录入。`TCRM_ADMIN_BASE_URL` 必须是无末尾斜杠的 HTTPS 后台地址（例如 `https://admin.example.com`）；将该域名登记为微店开放平台的回调域名。缺失或格式错误时后台启动失败。
3. 构建前端静态文件：`deploy/scripts/build-static.sh`（需要 Node.js 与 pnpm），输出到 `TCRM_STATIC_DIR`（默认 `deploy/www`）。
4. 启动：`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`。
5. 备份：用 cron 每天 03:00 执行 `deploy/backup/pg-backup.sh`；上线前做一次恢复演练（命令见脚本头部）。
6. 邮件服务（达人邀请与找回密码，任务 12a）：见下节「邮件服务（Resend）」。

## 邮件服务（Resend）

达人邀请与重置密码邮件经 Resend（`https://resend.com`）发送（设计说明书 §7.3）。免费额度 3,000 封/月、100 封/天，合同规模远低于上限。相关环境变量（见 `.env.example`）：

| 变量 | 说明 |
|---|---|
| `TCRM_MAIL_API_KEY` | Resend API Key；**为空时使用日志兜底**：邮件只写后台日志、不真正发出（仅限本机开发） |
| `TCRM_MAIL_FROM` | 发件人，例如 `T-CRM <no-reply@你的域名>`；配置了 Key 却没配本项时后台启动失败 |
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

- `t.<域名>` 只转发 `/t/…` 与 `/healthz` 到 crm-redirect，其余返回 404。`/t/` 按客户端 IP 限速 30 次/秒、突发 60，超出返回 429；`/healthz` 不限速。阈值偏松，是为了不误伤移动网络中大量用户共用出口 IP（CGNAT）的情况。边缘层（Cloudflare 频率限制规则）是主要防线，Nginx 限速是源站兜底；阈值可按实际流量调整。
- `kol.<域名>` / `admin.<域名>`：静态资源 + 单页应用回退；`/kol-api/`、`/admin-api/` 转发到 crm-admin；登录接口有 10 次/分钟（突发 5）的频率限制；登录验证码签发接口 `/admin-api/auth/captchas` 有 120 次/分钟（突发 30）的频率限制，后台另按每 IP 每分钟 120 张限制（`crm.auth.captcha.issue-per-minute`，ADR-017）。
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
