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
2. 填写 `deploy/.env`（`TCRM_DOMAIN`、数据库、`TCRM_REDIS_PASSWORD`（生产必填）、首次超管、跳转回退地址、IP 哈希盐等）。
3. 构建前端静态文件：`deploy/scripts/build-static.sh`（需要 Node.js 与 pnpm），输出到 `TCRM_STATIC_DIR`（默认 `deploy/www`）。
4. 启动：`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build`。
5. 备份：用 cron 每天 03:00 执行 `deploy/backup/pg-backup.sh`；上线前做一次恢复演练（命令见脚本头部）。

资源限制按设计说明书 §10：PostgreSQL 2G（`shared_buffers=512MB`）、Redis 1.5G（`maxmemory 1280mb`、`noeviction`）、crm-admin 2G、crm-redirect 512M。只有 nginx 映射 80/443；PostgreSQL、Redis、后台、跳转服务只在 compose 网络内可达。

## Nginx 说明

- `t.<域名>` 只转发 `/t/…` 与 `/healthz` 到 crm-redirect，其余返回 404。
- `kol.<域名>` / `admin.<域名>`：静态资源 + 单页应用回退；`/kol-api/`、`/admin-api/` 转发到 crm-admin；登录接口有 10 次/分钟（突发 5）的频率限制。
- 响应头（规范 06）：`X-Content-Type-Options`、`Referrer-Policy`、HSTS（一年）、`X-Frame-Options`，两个前端另加 `Content-Security-Policy`。
- 客户端 IP：Nginx **必须**只接受 Cloudflare 回源（或按站点覆盖/清洗转发头），否则 ADR-009 前提不成立。本模板仅当对端属于 Cloudflare 回源 IP 段（`nginx/snippets/cloudflare-real-ip.conf`，需随 Cloudflare 公告更新）时才采用 `CF-Connecting-IP`；转发给应用的 `X-Forwarded-For`、`CF-Connecting-IP`、`X-Real-IP` 一律由 Nginx 重写为解析后的地址，客户端自带的值不会到达应用。compose 网络使用固定网段 `172.30.0.0/24`，生产环境中 `crm-admin` / `crm-redirect` 的 `TCRM_TRUSTED_PROXIES` 已设为该网段。
- `${TCRM_DOMAIN}` 由 nginx 镜像在启动时替换（`NGINX_ENVSUBST_FILTER=^TCRM_`）。

## 可信代理（ADR-009）

生产 compose 已为 `crm-admin`、`crm-redirect` 固定设置 `TCRM_TRUSTED_PROXIES=172.30.0.0/24`，与上方 compose 网络网段一致；应用因此只信任来自 Nginx 的转发头。

**前提**：Nginx 必须只接受 Cloudflare 回源连接（本模板通过 `nginx/snippets/cloudflare-real-ip.conf` 的 `set_real_ip_from` 实现），或按站点覆盖/清洗转发头（本模板在 `proxy.conf` 中重写 `X-Forwarded-For` / `CF-Connecting-IP` / `X-Real-IP`）。若 Nginx 对任意来源照单全收客户端自带的转发头，ADR-009「不可信对端不读转发头」的前提不成立，点击去重与登录限流可被伪造。

## 第三方许可声明

两个 Dockerfile 把 backend、redirect、admin-ui、kol-portal 四份 `THIRD-PARTY-NOTICES` 复制到镜像的 `/licenses/`；`build-static.sh` 把同样四份放进两个静态目录的 `licenses/`（`https://admin.<域名>/licenses/backend.txt` 等）。
