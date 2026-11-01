# 任务 93 执行报告：生产编排与 TLS 加固

## 1. 结论

提示词第 2 节 17 个编号项全部实现，第 4 节 8 项验证中 7 项在独立 Compose 项目上实际运行通过，第 8 项（预览）用独立预览 Compose 项目验证，未使用 `preview.sh` 与共享中间件（原因见第 7 节，需负责人确认）。无迁移，无后端、跳转服务、前端代码改动。真实公网证书申请、真实 Cloudflare 回源认证、真实 AWS 安全组无法在本机验证，列入第 9 节「待上线验证」。

依据：[ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 1、3、4。

## 2. 版本与环境

- 分支 `task/93-prod-hardening`，工作树 `.worktrees/93-prod-hardening`，从 `a4d56c03` 检出。
- Docker 29.9.2，Compose v5.6.1，macOS（Docker Desktop）。nginx 1.30.5-alpine、postgres 17.11（均沿用模板）。
- 新增第三方：`certbot/dns-cloudflare:v5.8.0`（ADR-037 已批准，写死版本；Docker Hub 标签列表中存在，2026-09-01 发布，v5.9.0 为 2026-10-29 发布，取更早的稳定版）。只在运维脚本里运行，本机未拉取该镜像。
- 验证项目：`tcrm-t93-verify`（端口 19380、19343，网段 172.29.93.0/24，卷 `tcrm_t93_pgdata`、`tcrm_t93_redisdata`）；预览验证项目 `tcrm-t93-preview`（端口 19381～19383，网段 172.29.94.0/24）。验证用的 override、自签证书、测试 CA 与客户端证书都在工作树 `.tmp/`（Git 已忽略，不提交）。

## 3. 主要改动

| 文件 | 改动 |
|---|---|
| `deploy/nginx/snippets/tls-common.conf`（新） | 协议与会话参数，只写一份 |
| `deploy/nginx/snippets/tls.conf` | 改为 include `tls-common.conf`，注释说明仅用于 `t.`、`kol.` |
| `deploy/nginx/snippets/tls-public.conf`（新） | `public.pem`、`public.key`，include `tls-common.conf` |
| `deploy/nginx/snippets/cloudflare-origin-pull.conf`（新） | `ssl_client_certificate` + `ssl_verify_client on` |
| `deploy/nginx/snippets/cloudflare-origin-pull-ca.pem`（新） | Cloudflare Origin Pull CA（公开证书），文件头写来源、下载日期 2026-11-01、指纹 |
| `deploy/nginx/templates/default.conf.template` | `resolver 127.0.0.11 valid=10s ipv6=off;`；每个 server 用 `set` 变量保存上游，10 处 `proxy_pass` 全部改为变量（含 `t.`、`upload.`）；`admin.`、`upload.` 用 `tls-public.conf`；`t.`、`kol.`、`admin.` 引用回源认证；改 “Same certificate” 注释 |
| `deploy/nginx/preview/preview.conf` | 同步上游运行时解析（10 处 `proxy_pass`）；`preview.sh` 未改 |
| `deploy/scripts/renew-public-cert.sh`（新） | certbot DNS-01 申请/续期，落位 `public.pem`/`public.key`（600），`nginx -t` 后 reload；令牌文件必须 600 |
| `deploy/scripts/test-renew-public-cert.sh`（新） | 用 docker 桩离线验证落位、权限、重载顺序、令牌不进输出、权限不符拒绝运行 |
| `deploy/docker-compose.prod.yml` | `x-logging` 锚点（json-file、50m、5）供五个服务引用；postgres `shm_size: 256m`；两个数据卷 `external`（`tcrm_pgdata`、`tcrm_redisdata`）；`POSTGRES_USER/PASSWORD` 改用 `TCRM_DB_SUPERUSER_*`；挂载 `postgres/initdb` |
| `deploy/postgres/initdb/01-app-role.sh`（新） | 首次初始化空数据卷时创建非超级用户应用账号并设为库 owner；名称与密码用 psql `\getenv` 读取并由 psql 引用，不进命令行 |
| `deploy/.env.example` | 新增 `TCRM_DB_SUPERUSER_USERNAME/PASSWORD`、`TCRM_CERTBOT_CLOUDFLARE_INI`、`TCRM_CERTBOT_EMAIL`，更新 `TCRM_DB_USERNAME`、`TCRM_CERT_DIR` 注释 |
| `deploy/README.md` | 修正证书说明（原写源站证书需覆盖 `upload.` 是错的）；公开证书申请与续期；AWS 安全组；`live-restore`；删除 `TCRM_BOOTSTRAP_ADMIN_PASSWORD`；先创建数据卷；数据库账号分离；Nginx 说明（证书、回源认证、运行时解析） |
| `docs/03-设计/系统详细设计说明书.md` | §2.1 架构图与域名表（增加 `upload.`、源站证书与回源认证列，`admin.` 默认走代理）、修订记录 |
| `.gitignore` | 忽略 `deploy/certbot/`（certbot 账号与私钥状态目录） |

`deploy/backup/pg-backup.sh` 与 `pg_isready` 健康检查使用容器内 `$POSTGRES_USER`（现在是超级用户），无需修改，已实测。

## 4. 接口与表结构变更

无。无迁移。

## 5. 验证（提示词第 4 节，逐项实测）

项目 `tcrm-t93-verify`，自签证书，测试域名 `t93.test`，用 `--resolve`/`openssl s_client` 访问 127.0.0.1:19343。`$ra`＝带测试客户端证书（验证时用 override 把测试 CA 挂到 `cloudflare-origin-pull-ca.pem` 路径上，替换 Cloudflare CA）。

| # | 要求 | 命令/做法 | 结果 |
|---|---|---|---|
| 1 | 五服务 healthy，`nginx -t` 通过 | `docker compose ... ps`、`exec nginx nginx -t` | postgres、redis、crm-admin、crm-redirect 均 `healthy`；nginx 运行中（nginx 在模板中没有 healthcheck，故无 healthy 状态，本任务未新增）；`nginx -t` successful。重新用空卷从头启动后再次确认 |
| 2 | 重建 crm-admin 使 IP 变化，不 reload，10～15 秒恢复 | 先停 crm-admin，用 `docker run --ip` 占住旧 IP，再 `up -d crm-admin`；每秒 POST `admin.` captcha | 两次：IP 172.29.93.5→.7、.7→.5。第二次实测应用直连健康在 10 s，经 Nginx 同一次轮询（10 s）即 200，之前为 502；全程未 reload |
| 3 | 停 crm-admin 后重启 nginx | `stop crm-admin`，`restart nginx` | nginx 起来；`t./healthz` 200；`admin.` captcha 502（日志 `crm-admin could not be resolved`）；`upload.` 路径 502；`admin.` 静态首页 200 |
| 4 | 回源认证 | 无客户端证书；带证书；错误 CA 的证书 | 无证书（真实 Cloudflare CA）：`t.`、`kol.`、`admin.` 均 400 “No required SSL certificate was sent”，`upload.` 404（不需要证书）。用测试 CA 之外签发的证书访问也是 400。换测试 CA 并带测试客户端证书：`t./healthz` 200、`t./x` 404、`kol./` 200、`admin./` 200、`admin.` POST captcha 200；未知 SNI 在握手阶段被拒（`ssl_reject_handshake`），与 `ssl_verify_client` 不冲突 |
| 5 | `upload.` 300 MB | OPTIONS 预检；250 MB POST；Content-Length 315621376 的 POST；其它路径 | 预检 200；250 MB 经 Nginx 到达 crm-admin（应用返回 JSON 信封 415，因测试体无 multipart）；315621376 字节声明 → Nginx 413；`GET` 上传路径 403；`/`、`/admin-api/auth/captchas`、其它 POST 路径均 404。无需客户端证书 |
| 6 | 伪造转发头仍被重写 | 在 crm-admin 位置挂回显容器（别名 `crm-admin`/`crm-redirect`），请求带 `X-Forwarded-For: 6.6.6.6`、`CF-Connecting-IP: 7.7.7.7`、`X-Real-IP: 8.8.8.8`、`X-Forwarded-Proto: http` | `admin.`、`kol.`、`upload.`、`t.`（`/t/`、`/i/`）上游收到的 `X-Forwarded-For`、`CF-Connecting-IP`、`X-Real-IP` 均为对端地址（Docker 网关 192.168.65.1），`X-Forwarded-Proto: https` |
| 7 | 日志轮转、shm、非超级用户、Flyway | `docker inspect`；`psql` | 五个容器 `LogConfig` 均为 `json-file {"max-file":"5","max-size":"50m"}`；postgres `ShmSize=268435456`，容器内 `/dev/shm` 256M。`pg_roles`：应用账号 `rolsuper=f`（也无 createdb/createrole/replication），管理账号 `t`；库 owner 为应用账号；应用账号 `create role` 被拒；`flyway_schema_history` 90 条、失败 0、`installed_by` 全为应用账号（迁移文件 90 个），`Started TcrmAdminApplication`。迁移里无 `CREATE EXTENSION/ROLE/USER`（grep -i 无命中），未触发「停下来报告」 |
| 8 | 独立预览不受影响 | 见第 7 节 | `docker-compose.preview.yml` 另起项目 `tcrm-t93-preview`（连自己的 PostgreSQL/Redis，加别名）：三个入口 200（admin `/`、kol `/`、redirect `/healthz`），captcha POST 200，未知路径 404；重建 crm-admin 后不 reload，12 s 内从 502 恢复到 200；`nginx -t` 通过 |

补充实测：

- **路径不变（停下来报告项 2）**：对 10 个特殊 URI（`%2F`、`//`、`/../`、`;`、`%2e%2e`、中文百分号编码、重复参数、`%25` 等），经变量 `proxy_pass` 的上游收到的 URI 与客户端发送的完全一致，且与静态 `proxy_pass` 基线逐条相同（10/10，第一轮有 3 条是我的测试脚本问题，修复后重跑一致）。
- **`down -v` 保护**：对验证项目执行 `down -v`，两个 external 数据卷仍在（之后由我手动删除）。
- **空卷初始化脚本**：脚本最终版本用 `postgres:17.11` 一次性容器验证，用户名 `App-User`、密码含引号和 `$` 均能正确创建（非超级用户、owner 正确）；再用最终版本从空卷完整重跑第 1、7 项。
- **`pg-backup.sh`**：对验证栈执行（副本把 compose 命令指向验证栈）：生成 `.dump` 并通过 `pg_restore --list`；另在临时库 `pg_restore --no-owner` 恢复成功，`flyway_schema_history` 90 条。脚本末尾的保留清理在 macOS 因 BSD `date` 失败，属原有行为（生产为 Linux，保留规则测试在 Linux 容器中通过）。
- 80 端口返回 301 到 https。

## 6. 检查（子代理，sonnet）

| 检查 | 结果 |
|---|---|
| `docker compose -f deploy/docker-compose.{dev,preview,prod}.yml --env-file deploy/.env.example config -q` | 三个均退出码 0 |
| `python3 deploy/scripts/check-healthchecks.py` | 0 |
| `python3 deploy/scripts/check-compose-env.py` | 0 |
| `bash deploy/backup/test-retention.sh` | macOS 的 BSD `date` 不支持，失败；在 Linux 容器（`postgres:17.11`）中 `retention rule OK`，退出码 0 |
| `bash deploy/scripts/test-renew-public-cert.sh` | 0 |
| shellcheck v0.10.0（容器）`-S warning` 检查四个脚本 | 0，无 warning；全级别仅 3 条 info（`.env` source 不跟随 SC1091 两条，`pg-backup.sh` 单引号内变量 SC2016，均为既有写法） |
| `nginx -t`（渲染生产模板、`preview.conf`，一次性容器） | 均成功 |
| `git status`/`git diff --stat` | 8 个修改文件、新增文件符合预期；ADR-037 与 93～96 提示词、操作清单保持未跟踪未修改 |

不跑 `mvn`、前端检查。部署工作流里还有镜像构建步骤（`docker/build-push-action`），本任务未改 Dockerfile，本机用 `up --build` 实际构建了两个镜像。

## 7. 偏离与说明

1. **第 4 节第 8 项（`$preview-verify`）未按字面执行**：`preview.sh up` 会调用 `local-infra.sh up`、连接共享中间件 `tcrm-local` 并运行共享库的 Flyway，与本次任务「不连共享中间件」的要求冲突。改为用 `docker-compose.preview.yml` 另起项目 `tcrm-t93-preview`，连接验证栈自己的 PostgreSQL/Redis（额外建网络 `tcrm-t93-infra` 加 `tcrm-postgres`/`tcrm-redis` 别名），静态文件用占位页，因此**没有**运行 `preview.sh` 本身，也**没有**构建真实前端。`preview.sh` 未改。预览端口用了 19381～19383（任务只分配了 19380、19343），启动前确认无占用。
2. 验证 override 把测试 CA 挂到 `/etc/nginx/snippets/cloudflare-origin-pull-ca.pem`，用来替换 Cloudflare CA（提示词允许）；Cloudflare 真实 CA 本身只验证了「无客户端证书/错误 CA 被拒」，未验证真实 Cloudflare 客户端证书被接受。
3. `renew-public-cert.sh` 只对 `certbot` 单个证书名 `tcrm-public` 管理两个域名；Let's Encrypt 邮箱 `TCRM_CERTBOT_EMAIL` 必填（提示词未明说，certbot 非交互需要）。
4. 为保证脚本可测，新增了 `test-renew-public-cert.sh`（提示词未要求）；未接入 `.github/workflows/deploy.yml`，如需 CI 执行请负责人决定。
5. `.gitignore` 增加 `deploy/certbot/`（提示词未列出，防止账号私钥入库）。
6. `pg-backup.sh` 无改动；README 补充了「备份用管理超级用户执行」的说明。
7. nginx 服务没有 healthcheck，第 4 节第 1 项的「五个服务全部 healthy」中 nginx 为 running，未新增 healthcheck（避免超范围，监控属任务 96）。

## 8. 复核表（提示词第 2 节）

由复核子代理（只读，sonnet）逐条核对，结果均为「满足」；我补充了运行证据。

| 项 | 要求 | 实现位置 | 结论 |
|---|---|---|---|
| 2.1(1) | `tls-public.conf`，`public.pem/key`；`admin.`、`upload.` 用，`t.`、`kol.` 仍用 `tls.conf`；公共参数不写两份 | `snippets/tls-public.conf`、`tls-common.conf`、`tls.conf`；模板各 server 的 include | 满足（`nginx -t`；`openssl s_client` 实测 `t.`、`kol.` 返回 origin 证书，`admin.`、`upload.` 返回 public 证书） |
| 2.1(2) | `renew-public-cert.sh`：写死版本 certbot，DNS-01，落位 600 并 reload；令牌从 600 文件读取，不进 `.env`、日志 | `scripts/renew-public-cert.sh` | 满足（桩测试；真实申请待上线验证） |
| 2.1(3) | 脚本写明 cron 与首次申请 | 脚本头注释、README「公开证书的申请与续期」 | 满足 |
| 2.2(1) | `cloudflare-origin-pull.conf`；仅 `t.`、`kol.`、`admin.` 引用 | `snippets/cloudflare-origin-pull.conf`；模板 3 处 include | 满足（实测见第 5 节 #4） |
| 2.2(2) | Origin Pull CA 入库，文件头注来源日期 | `snippets/cloudflare-origin-pull-ca.pem` | 满足（openssl 核对 subject、有效期、指纹） |
| 2.2(3) | README 写明 `admin.` 改灰云删引用，不加开关 | README「Nginx 说明」、设计说明书 §2.1 | 满足 |
| 2.3(1) | `resolver ... valid=10s ipv6=off;`，server 内变量，`proxy_pass` 用变量无 URI | 模板、`preview.conf` | 满足（实测 #2、#3、#6 与 URI 对比） |
| 2.3(2) | 所有反代 location 都改，含 `upload.` | 模板 10 处 | 满足（grep 无静态 `proxy_pass http`） |
| 2.3(3) | 同步 `preview.conf`，`preview.sh` 不改 | `preview.conf` 10 处；`preview.sh` 无改动 | 满足 |
| 2.4(1) | 日志轮转 json-file 50m/5，锚点一处 | `docker-compose.prod.yml` `x-logging` | 满足（#7） |
| 2.4(2) | `shm_size: 256m` | postgres 服务 | 满足（#7） |
| 2.4(3) | 数据卷 external，`tcrm_pgdata`/`tcrm_redisdata`；README 先 `docker volume create` | compose `volumes`、README | 满足（`down -v` 实测卷保留） |
| 2.4(4) | 超级用户与应用账号分离；initdb；健康检查/备份同步 | compose、`postgres/initdb/01-app-role.sh`、`.env.example` | 满足（#7、`pg-backup.sh` 实测） |
| 2.4(5) | `.env.example` 同步并有用途注释 | `deploy/.env.example` | 满足 |
| 2.5(1) | README：证书说明、AWS 安全组、`live-restore`、删除引导密码 | `deploy/README.md` | 满足（残留的「源站证书需覆盖 `upload.`」已删除） |
| 2.5(2) | 模板 “Same certificate” 注释 | 模板 `upload.` 段 | 满足 |
| 2.5(3) | 设计说明书 §2.1 域名表与架构图 | `系统详细设计说明书.md` | 满足 |

不做项：未改后端、跳转服务、前端；未改 `docs/04-开发任务/README.md`；未触及备份（任务 94）、镜像发布（95）、监控（96）；未调其他 PostgreSQL 参数和资源限制；未访问真实 Cloudflare、AWS、Let's Encrypt。

停下来报告项：四项均未触发（迁移无需超级用户；变量 `proxy_pass` 不改变转发路径；`ssl_verify_client` 与默认 server `ssl_reject_handshake` 不冲突；未改后端）。

## 9. 待上线验证（本机无条件，未验证，不应视为通过）

- 真实域名上 `certbot/dns-cloudflare:v5.8.0` 的 DNS-01 申请与续期（含 Cloudflare 令牌权限、`--dns-cloudflare-propagation-seconds 30` 是否够用、Let's Encrypt 速率限制）；该镜像本机未拉取。
- Cloudflare 真实 Authenticated Origin Pulls：`t.`、`kol.`、`admin.` 经 Cloudflare 回源时客户端证书被接受；`admin.` 橙云下 Full (strict) 能接受 Let's Encrypt 证书。
- 公网上 `upload.` 的 300 MB 浏览器直传（含 CORS 预检链路与 `url.upload` 配置）。
- 经 Cloudflare 时 `CF-Connecting-IP` 取值（`set_real_ip_from` 未改动；本机只验证非 Cloudflare 对端的伪造头被重写）。
- AWS 安全组、`/etc/docker/daemon.json` 的 `live-restore`（README 已写明，主机配置不在仓库）。
- 生产 Linux 主机上的 `pg-backup.sh` 保留清理（本机 macOS 仅验证了转储与恢复）。
- 真实前端构建物下的预览（占位页代替）。

## 10. 遗留与建议

- ADR-037 已提交到 main；本 PR 引用其路径，合并后链接有效。
- 建议把 `test-renew-public-cert.sh` 接入 `.github/workflows/deploy.yml`（需负责人决定，本任务未改工作流）。
- 现有生产卷若已有数据，`initdb` 不会补建应用账号（README 已写明）；项目尚未上线，不涉及。
- 证书到期监控属任务 96。
- `preview.sh` 中关于「Nginx 在启动时解析静态主机名」的注释已不符合现状（`preview.sh` 不在本任务改动范围，保留其 reload 无害），建议后续顺手清理。

## 11. 收尾清理

只删除自己创建的资源：

```bash
docker compose -p tcrm-t93-verify -f deploy/docker-compose.prod.yml -f .tmp/override.yml --env-file .tmp/verify.env down
docker compose -p tcrm-t93-preview -f deploy/docker-compose.preview.yml --env-file .tmp/preview.env down
docker volume rm tcrm_t93_pgdata tcrm_t93_redisdata
docker network rm tcrm-t93-infra
docker rmi tcrm-t93-verify-crm-admin tcrm-t93-verify-crm-redirect tcrm-t93-preview-crm-admin tcrm-t93-preview-crm-redirect
rm -rf deploy/www-preview
```

已执行；`docker ps -a`、`docker volume ls`、`docker network ls`、`docker images` 中不再有 `t93` 资源；`tcrm-local`、`tcrm-preview` 容器未受影响（保持运行）。临时文件在工作树 `.tmp/`（Git 忽略）。

## 12. PR 与 CI

见 PR 描述；推送后 `gh pr checks` 结果见本节末尾补充（不轮询）。
