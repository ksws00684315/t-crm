# 任务 93：生产编排与 TLS 加固

> **执行环境**：本机。
> - **前置**：无。任务 94～96 都改 `docker-compose.prod.yml`，须按 93 → 94 → 95 → 96 顺序执行，不并行。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/93-prod-hardening`，分支 `task/93-prod-hardening`，从最新 `main` 检出。
> - 不连共享中间件，不启动本地后端开发进程。无迁移。依赖：certbot 镜像（ADR-037 已批准），写死版本。
> - 验证用独立 Compose 项目 `tcrm-t93-verify`，端口 `19380`、`19343`。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 1、3、4：本任务的依据；
> - [ADR-009](../06-架构决策/ADR-009-可信代理与客户端IP解析.md)：转发头规则不能被改坏；
> - `deploy/README.md`「生产部署」「Nginx 说明」「可信代理」三节；
> - 设计说明书 §2.1 的域名表。

## 1. 背景与需求来源

本任务不对应进度表行，依据是 ADR-037（负责人 2026-11-01 确认按方案执行）。

## 2. 要求

### 2.1 公开证书（修复上传不可用）

现状：`upload.` 灰云直连，却通过 `tls.conf` 使用 Cloudflare 源站证书，浏览器不信任该证书，上传会失败。`deploy/README.md` 还写着源站证书要覆盖 `upload.`，这是错的。

1. 新增 `snippets/tls-public.conf`，证书文件为 `${TCRM_CERT_DIR}` 下的 `public.pem`、`public.key`。`admin.`、`upload.` 使用它；`t.`、`kol.` 继续用 `tls.conf`。公共的协议与会话参数不要写两份。
2. 新增 `deploy/scripts/renew-public-cert.sh`：用写死版本的 `certbot/dns-cloudflare` 镜像，通过 DNS-01 申请并续期 `admin.`、`upload.` 两个域名；续期成功后把证书放到约定路径（权限 600）并 `nginx -s reload`。Cloudflare 令牌从 `TCRM_CERTBOT_CLOUDFLARE_INI`（权限 600 的文件）读取，不进 `.env`，不进日志。
3. 脚本写明定时方式（host cron 每天一次）和首次申请方法。

### 2.2 源站保护

1. 新增 `snippets/cloudflare-origin-pull.conf`：`ssl_client_certificate` 加 `ssl_verify_client on`，只在 `t.`、`kol.`、`admin.` 三个 server 引用；`upload.` 不引用。
2. Cloudflare 的 Origin Pull CA 证书从官方文档下载后入库，文件头注释写明来源和下载日期（做法同 `cloudflare-real-ip.conf`）。它是公开证书。
3. README 写明：`admin.` 若经实测改为灰云，要删掉该 server 的这一行引用。不要加开关。

### 2.3 Nginx 上游运行时解析

现状：`proxy_pass http://crm-admin:8080` 只在启动时解析。crm-admin 重建后 IP 可能变化，出现 502；Nginx 重启时若 crm-admin 没在运行，Nginx 起不来，`t.` 跳转一起中断。

1. 使用 Docker DNS：`resolver 127.0.0.11 valid=10s ipv6=off;`，在 server 内用变量保存上游，`proxy_pass` 使用变量，不带 URI，保持原请求路径不变。
2. 所有反代 location 都改，包括 `upload.`。
3. 按 README 第 101 行的规则，同步 `deploy/nginx/preview/preview.conf`。`preview.sh` 不改。

### 2.4 Compose

1. 所有服务加日志轮转：`json-file`，`max-size: 50m`，`max-file: "5"`，用 YAML 锚点写一处。
2. postgres 加 `shm_size: 256m`。
3. `pgdata`、`redisdata` 改为 `external: true`，名称 `tcrm_pgdata`、`tcrm_redisdata`，防止 `down -v` 删数据。README 写明首次部署要先 `docker volume create`。
4. 分离数据库账号：`POSTGRES_USER` 改用新变量 `TCRM_DB_SUPERUSER_USERNAME` / `TCRM_DB_SUPERUSER_PASSWORD`；挂载初始化脚本 `deploy/postgres/initdb/` 创建非超级用户的应用账号（`TCRM_DB_USERNAME`）并作为 `TCRM_DB_NAME` 的 owner。crm-admin 继续用应用账号，备份和 healthcheck 涉及的用户名同步检查。`deploy/backup/pg-backup.sh` 也要能正常工作。
5. `.env.example` 同步，注释写清用途。

### 2.5 文档

1. `deploy/README.md`：修正第 132 行及上线检查里的证书说明；补 AWS 安全组说明（Docker 发布端口绕过 ufw，防火墙以云安全组为准；只开 80、443，SSH 限来源）；补 `/etc/docker/daemon.json` 的 `live-restore`；补「首次启动后从 `.env` 删除 `TCRM_BOOTSTRAP_ADMIN_PASSWORD`」。
2. 模板 `default.conf.template` 第 164 行的 “Same certificate” 注释要改。
3. 设计说明书 §2.1 的域名表与架构图，按 ADR-037 决定 3 修订。

## 3. 不做

- 不改后端、跳转服务、前端代码。
- 不做备份（任务 94）、镜像发布和前端 `current` 目录（任务 95）、监控（任务 96）。
- 不调 PostgreSQL 其他参数，不改资源限制。
- 不访问真实 Cloudflare、AWS、Let's Encrypt 账号。
- 不修改 `docs/04-开发任务/README.md`。

## 4. 验证

用仓库外的临时 override 文件（放工作树的 `.tmp/`，不提交）启动真实生产模板：项目名 `tcrm-t93-verify`，改端口、网段（避开 `172.30.0.0/24`）、数据卷名，证书用自签。**只允许删除这个项目自己的容器和卷**，不得碰 `tcrm-local`、`tcrm-preview`。

必须逐项验证并把命令和结果写进报告：

1. 五个服务全部 healthy，`nginx -t` 通过。
2. 重建 crm-admin 使 IP 变化后，不 reload，`admin.` 接口在 10～15 秒内恢复。
3. 停 crm-admin 后重启 nginx：nginx 能起来，`t./healthz` 返回 200，`admin.` API 返回 502。
4. 无客户端证书访问 `t.`、`kol.`、`admin.` 被拒；带测试客户端证书（验证时临时替换 CA）能通过；`upload.` 无须客户端证书。
5. `upload.` 用 300 MB 上限的预检和 POST 能到达 crm-admin；其它路径 404。
6. 伪造 `X-Forwarded-For`、`CF-Connecting-IP` 仍被重写。
7. `docker inspect` 确认日志轮转、`shm_size`；数据库内确认应用账号 `rolsuper = false`，Flyway 全部迁移以该账号跑通，后台启动成功。
8. 用 `$preview-verify` 跑独立预览，确认预览未受影响。

`renew-public-cert.sh` 无法连真实服务：用 shellcheck 检查，并用桩替换 `docker run` 验证文件落位和 reload，真实申请列入「待上线验证」。

## 5. 验收与交付

- **检查**：部署三项检查（`config -q`、`check-healthchecks.py`、`check-compose-env.py`），`bash deploy/backup/test-retention.sh`。不跑 `mvn`、前端检查。交给子代理。
- **浏览器验收**：不涉及界面，不做。
- **执行报告**：`docs/04-开发任务/93-生产编排与TLS加固-执行报告.md`，逐条复核表覆盖 §2 每一项。
- **PR**：不合并、不推 main。推送后看一次 `gh pr checks`，结果写进报告，不轮询。

## 6. 停下来报告

- 有迁移依赖超级用户权限（例如 `CREATE EXTENSION`），应用账号跑不通。不得修改已合并的迁移；
- 变量形式的 `proxy_pass` 改变了任何现有路径的转发结果；
- `ssl_verify_client` 与 `ssl_reject_handshake` 默认 server 存在冲突；
- 需要改后端代码才能完成。
