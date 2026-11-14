# 阿里云生产装机与上线手册（RUNBOOK-ALIYUN）

> **用途**：在一台空白阿里云 ECS（Ubuntu 24.04 LTS，美国弗吉尼亚 us-east-1）上从零逐条照做，直到上线检查全部通过。与 [`RUNBOOK.md`](RUNBOOK.md)（AWS 版）是**独立的两份**：AWS 主机照 AWS 版，阿里云主机照本版，脚本与模板互不混用。
>
> **备份口径（任务 150，负责人 2026-11-12 决定）**：先用阿里云自带备份，不建 OSS 桶。三层：pgBackRest 本地 posix 仓库（WAL 连续归档，本地时间点恢复窗口约 5 分钟）＋每日加密 `pg_dump` 留本机＋**ECS 自动快照策略（每天、保留 7 天）作为异地兜底**。整机丢失从快照重建，最多丢一天数据，**达不到 ADR-037 的约 5 分钟目标**；接 OSS 后恢复该目标（OSS 适配代码搁置在分支 `task/149-aliyun-backup-creds`）。恢复目标变更须按待确认清单第 35 项告知客户。
>
> 已定前提：Ubuntu 24.04 x86_64 / us-east-1；登录走**云助手会话管理**（控制台 → 实例 → 远程连接 → 会话管理），不开 22 端口；AI 代理经 aliyun CLI `RunCommand` 执行命令；`sudo -i` 以 root 操作；仓库克隆到 `/opt/tcrm`、按版本标签检出；定时任务全放 root crontab；主机时区 UTC；数据目录统一放 `/var/lib/tcrm/`。
>
> 占位符：`<域名>`、`<版本>`（标签 `v<版本>`）、`<公网IP>`。GitHub 与 Cloudflare 令牌在第 3 章生成，按提示输入（不回显、不进 shell 历史）。除标注【控制台】【负责人】【浏览器】外，都在主机上以 root 执行。

## 0. 目录规划

| 路径 | 用途 | 权限 |
|---|---|---|
| `/opt/tcrm` | 仓库（`git clone`，属主 root） | 755 |
| `/var/lib/tcrm/certs` | 证书（`TCRM_CERT_DIR`）：`origin.pem`、`origin.key`、`public.pem`、`public.key` | 755，私钥 600 |
| `/var/lib/tcrm/www` | 前端静态（`TCRM_STATIC_DIR`）：`releases/<版本>/`、`current` 软链 | 755 |
| `/var/lib/tcrm/backups` | 加密逻辑备份（`TCRM_BACKUP_DIR`） | 700 |
| `/var/lib/tcrm/pgbackrest-repo` | pgBackRest **本地 posix 仓库**（`TCRM_PGBACKREST_REPO_DIR`），属主 uid 999 | 750，属主 999:999 |
| `/var/lib/tcrm/secrets` | 备份口令与 Cloudflare 令牌文件 | 700，文件 600 |
| `/var/lib/tcrm/ops-state` | 告警去重状态（`TCRM_OPS_STATE_DIR`） | 700 |
| `/var/lib/tcrm/certbot` | certbot 账号与证书状态 | 700 |
| Docker 卷 `tcrm_pgdata`、`tcrm_redisdata` | 数据库与 Redis 数据（external 卷） | — |

与 AWS 版的区别：**没有** `pgbackrest-creds` 目录（posix 仓库不需要凭证）。

## 1. 开机前决定与账号【负责人】

- 客户名下阿里云账号（国内站即可，美国地域无需备案），根账号开 MFA、日常不用；设预算告警。
- 机型 ECS c7a.xlarge（4 核 8G，AMD），ESSD PL0 200GB，按使用流量带宽（峰值 ≥ 50Mbps，300MB 上传不逼近 Nginx 300 秒超时）。
- 密钥保管：`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、`PGBACKREST_REPO1_CIPHER_PASS`、备份口令、数据库与 Redis 密码、`.env` 全文进密码管理器，**与备份分开存放**。全新部署生成新密钥；AWS 旧密钥归档保留，账户若恢复仍可导出旧备份核对。

## 2. ECS、安全组、快照与元数据【控制台】

1. **安全组**：入站只开 `80/tcp`、`443/tcp`，来源 `0.0.0.0/0`（`upload.` 灰云需任意来源直连）；**不开 22**。PostgreSQL、Redis、后台、跳转服务不发布端口，不开任何入站。出站默认全放行。
2. **元数据访问模式**：实例 → 更多 → 实例设置 → 设置元数据访问模式，选**仅加固模式**（`HttpTokens=required`，等价 IMDSv2）。
3. **DOCKER-USER 隔离**（主机初始化后执行，第 4.4 节）：阿里云没有 hop limit 参数，容器可经网桥路由到元数据地址，须加 iptables 规则。当前未挂实例 RAM 角色，此规则为纵深防御，将来挂角色时是硬要求。
4. **自动快照策略**：ECS → 存储与快照 → 自动快照策略，创建策略（每天一次、保留 7 天），应用到系统盘。购买时已带 `default_policy` 的核对设置一致。**快照是整机丢失的唯一异地兜底，必须确认已生效。**
5. **公网 IP 转 EIP**（建议）：实例 → 网络与安全组 → 公网 IP 转弹性公网 IP，转换后 IP 不随实例释放，DNS 更稳。记下 `<公网IP>`。
6. 不创建 OSS 桶、不创建实例 RAM 角色（本阶段不需要）。

## 3. Cloudflare、域名与其他服务【Cloudflare / 负责人】

与 AWS 版 RUNBOOK 第 5 章完全相同（Cloudflare 与云厂商无关），照做：

1. 域名开注册商锁和 MFA。
2. DNS 五条 A 记录指向 `<公网IP>`：`t.`、`kol.`、`admin.` 橙云（Proxied）；`upload.` 灰云（DNS only）；根域名 `@` 橙云（微店验证文件）。
3. SSL/TLS 模式 **Full (strict)**；创建源站证书（覆盖 `<域名>` 与 `*.<域名>`），PEM 与私钥立即保存。
4. 开启 **Authenticated Origin Pulls**。
5. 创建 API 令牌（仅本 Zone 的 Zone → DNS → Edit，certbot 用）。
6. WAF 频率限制规则按 README「Cloudflare 频率限制规则」表创建。
7. Resend 发件域名与 SPF/DKIM/DMARC、API Key；UptimeRobot 三条探测；Healthchecks 心跳；飞书机器人（关键词放行 `ALERT` 与 `RECOVERED`、不开签名校验）；GitHub `read:packages` 令牌。
8. 微店开放平台：授权回调 `https://admin.<域名>/admin-api/integration/weidian/oauth/callback`，下载 `weidian_open.json` 备用（第 5 章放到静态目录根）。

## 4. 主机初始化【主机 root】

### 4.1 系统更新与时区

```bash
apt-get update && apt-get -y upgrade
[ -f /var/run/reboot-required ] && reboot   # 升级了内核就重启，会话管理重连后继续
timedatectl set-timezone UTC
timedatectl   # 期望 Etc/UTC，NTP active
```

### 4.2 Docker Engine 与 Compose 插件（官方 apt 源）

与 AWS 版 RUNBOOK 第 6.2 节命令相同（Docker 官方 Ubuntu 仓库）：

```bash
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
docker version && docker compose version
```

### 4.3 live-restore

```bash
printf '{\n  "live-restore": true\n}\n' > /etc/docker/daemon.json
systemctl reload docker
docker info --format '{{.LiveRestoreEnabled}}'   # 期望 true
```

### 4.4 元数据隔离规则

```bash
iptables -I DOCKER-USER -d 100.100.100.200 -j DROP
```

持久化（重启不丢）：`apt-get install -y iptables-persistent`，安装时保存当前规则；或把规则写进 `/etc/rc.local` 等等效机制。确认：`iptables -L DOCKER-USER -n | grep 100.100.100.200` 有 DROP 行。

### 4.5 命令齐套核对

不需要 AWS CLI。脚本用到的外部命令：

```bash
apt-get install -y git
for c in git docker gpg curl openssl crontab awk grep sed find date stat install cmp readlink mktemp; do
  command -v "$c" > /dev/null || echo "缺少：$c"
done   # 期望无输出
```

## 5. 获取代码、目录与数据卷【主机 root】

### 5.1 克隆仓库并检出标签

```bash
git clone https://github.com/ksws00684315/t-crm.git /opt/tcrm
cd /opt/tcrm
git fetch --tags
git checkout v<版本>
```

### 5.2 数据目录

```bash
install -d -m 755 /var/lib/tcrm
install -d -m 755 /var/lib/tcrm/certs
install -d -m 755 /var/lib/tcrm/www
install -d -m 700 /var/lib/tcrm/backups
install -d -m 700 /var/lib/tcrm/secrets
install -d -m 750 -o 999 -g 999 /var/lib/tcrm/pgbackrest-repo   # posix 仓库，postgres 容器内 uid 999 可写
install -d -m 700 /var/lib/tcrm/ops-state
install -d -m 700 /var/lib/tcrm/certbot
```

确认：`stat -c '%u %a' /var/lib/tcrm/pgbackrest-repo` 输出 `999 750`；目录为空（`ls -A` 无输出）。

### 5.3 外部数据卷（只做一次）

```bash
docker volume create tcrm_pgdata
docker volume create tcrm_redisdata
```

### 5.4 GHCR 登录

```bash
read -rs TCRM_GH_TOKEN     # 第 3 章的 GitHub 令牌（不回显、不进历史）
printf '%s' "$TCRM_GH_TOKEN" | docker login ghcr.io -u ksws00684315 --password-stdin
unset TCRM_GH_TOKEN
grep -c ghcr.io /root/.docker/config.json   # 期望 ≥ 1
```

### 5.5 微店验证文件

把第 3 章下载的 `weidian_open.json` 放到 `/var/lib/tcrm/www/weidian_open.json`（**不要**放进 `releases/` 或 `current/`，发布切软链会丢）。

## 6. 填写 `deploy/.env`【主机 root】

### 6.1 口令/令牌文件

```bash
install -m 600 /dev/null /var/lib/tcrm/secrets/backup.pass
openssl rand -base64 32 > /var/lib/tcrm/secrets/backup.pass
install -m 600 /dev/null /var/lib/tcrm/secrets/cloudflare.ini
read -rs TCRM_CF_TOKEN
printf 'dns_cloudflare_api_token = %s\n' "$TCRM_CF_TOKEN" > /var/lib/tcrm/secrets/cloudflare.ini
unset TCRM_CF_TOKEN
stat -c '%a' /var/lib/tcrm/secrets/*   # 期望都 600
```

口令另抄进密码管理器。

### 6.2 `.env` 逐项

```bash
cp /opt/tcrm/deploy/.env.example /opt/tcrm/deploy/.env
chmod 600 /opt/tcrm/deploy/.env
nano /opt/tcrm/deploy/.env
```

**阿里云特有取值**（其余变量与 AWS 版 RUNBOOK 第 8.2 节表相同，照填）：

| 变量 | 取值 | 说明 |
|---|---|---|
| `TCRM_COMPOSE_FILE` | `/opt/tcrm/deploy/docker-compose.aliyun.yml` | **新增行**。release.sh（任务 150 起）、ops-check、备份脚本都读它 |
| `PGBACKREST_REPO1_TYPE` | `posix` | 本地仓库 |
| `PGBACKREST_REPO1_PATH` | 留默认 `/pgbackrest` | 容器内仓库路径 |
| `TCRM_PGBACKREST_REPO_DIR` | `/var/lib/tcrm/pgbackrest-repo` | **新增行**，宿主机仓库目录 |
| `PGBACKREST_REPO1_CIPHER_PASS` | `openssl rand -base64 32` | 仓库加密，密码管理器另存 |
| `TCRM_BACKUP_S3_BUCKET` | **留空** | 逻辑备份只留本机，不上传 |
| `PGBACKREST_REPO1_S3_BUCKET`、`PGBACKREST_REPO1_S3_REGION`、`PGBACKREST_REPO1_S3_ENDPOINT`、`AWS_REGION` | **删除或保持注释** | posix 不用；空值会让 pgBackRest 报错 |
| `TCRM_PGBACKREST_CREDS_DIR` | **删除或保持注释** | 阿里云 compose 不挂凭证目录 |

其余必填：`TCRM_DOMAIN`、`TCRM_CERT_DIR=/var/lib/tcrm/certs`、`TCRM_STATIC_DIR=/var/lib/tcrm/www`、`TCRM_BACKUP_DIR=/var/lib/tcrm/backups`、`TCRM_BACKUP_PASSPHRASE_FILE=/var/lib/tcrm/secrets/backup.pass`、`TCRM_CERTBOT_CLOUDFLARE_INI=/var/lib/tcrm/secrets/cloudflare.ini`、`TCRM_CERTBOT_EMAIL`、`TCRM_CERTBOT_STATE_DIR=/var/lib/tcrm/certbot`、`TCRM_OPS_STATE_DIR=/var/lib/tcrm/ops-state`、数据库与 Redis 凭证（`openssl rand -base64 24`，应用账号与超级用户不同名）、`TCRM_SECRET_KEY`（`openssl rand -base64 32`）、`TCRM_IP_HASH_SALT`（`openssl rand -hex 32`）、四个站点 URL、`TCRM_CORS_ALLOWED_ORIGINS=https://admin.<域名>`、`TCRM_MAIL_API_KEY`、`TCRM_MAIL_FROM`、引导管理员账号密码、飞书 webhook、心跳 URL。

确认：`stat -c '%a' /opt/tcrm/deploy/.env` 输出 600。

## 7. 证书【主机 root + Cloudflare】

与 AWS 版第 9 章相同：

```bash
nano /var/lib/tcrm/certs/origin.pem   # 第 3 章源站证书 PEM
nano /var/lib/tcrm/certs/origin.key   # 私钥
chmod 600 /var/lib/tcrm/certs/origin.key
cd /opt/tcrm
deploy/scripts/renew-public-cert.sh   # 首次申请 admin./upload. 的 Let's Encrypt 证书
openssl x509 -noout -issuer -subject -ext subjectAltName -in /var/lib/tcrm/certs/public.pem
```

期望：四个证书文件齐全，私钥 600，公开证书 issuer 为 Let's Encrypt、SAN 含 `admin.` 与 `upload.`。

## 8. 上线前核对（人工核对表）

| # | 核对项 | 确认命令 / 位置 | 期望 |
|---|---|---|---|
| 1 | 架构 x86_64 | `uname -m` | `x86_64` |
| 2 | 时区 UTC | `timedatectl` | `Etc/UTC` |
| 3 | Docker / Compose | `docker version`；`docker compose version` | 正常输出 |
| 4 | live-restore | `docker info --format '{{.LiveRestoreEnabled}}'` | `true` |
| 5 | 元数据隔离规则 | `iptables -L DOCKER-USER -n \| grep 100.100.100.200` | DROP 行 |
| 6 | 元数据仅加固模式【控制台】 | 实例元数据访问模式 | 仅加固模式 |
| 7 | 自动快照策略【控制台】 | 系统盘关联的策略 | 每天、保留 7 天、已启用 |
| 8 | 安全组只开 80/443【控制台】 | 入方向规则 | 仅两条，无 22 |
| 9 | `.env` 权限 600 | `stat -c '%a' /opt/tcrm/deploy/.env` | `600` |
| 10 | 必填项全有值 | 下方循环 | 无 `缺:` 输出 |
| 11 | 外部数据卷 | `docker volume ls --format '{{.Name}}' \| grep -c '^tcrm_'` | `2` |
| 12 | 仓库目录属主/权限 | `stat -c '%u %a' /var/lib/tcrm/pgbackrest-repo` | `999 750` |
| 13 | 证书齐全、私钥 600 | `ls /var/lib/tcrm/certs`；`stat -c '%a' …/*.key` | 四文件、600 |
| 14 | 只有 Nginx 发布端口 | `docker compose -f deploy/docker-compose.aliyun.yml --env-file deploy/.env config \| grep -c 'published:'` | `2` |
| 15 | 磁盘余量 | `df -h /var/lib/docker` | Avail ≥ 约 8 GiB（归档队列 4G + max_wal_size 1G + 突发） |
| 16 | crontab 已安装（终检） | `crontab -l \| grep -c '/opt/tcrm/deploy'` | ≥ 4 |

第 10 项循环（posix 不含 S3 两项）：

```bash
cd /opt/tcrm
for k in TCRM_IMAGE_REGISTRY TCRM_DOMAIN TCRM_DB_USERNAME TCRM_DB_PASSWORD \
TCRM_DB_SUPERUSER_USERNAME TCRM_DB_SUPERUSER_PASSWORD TCRM_REDIS_PASSWORD \
TCRM_LINK_BASE_URL TCRM_KOL_PORTAL_BASE_URL TCRM_ADMIN_BASE_URL TCRM_REDIRECT_FALLBACK_URL \
TCRM_SECRET_KEY TCRM_IP_HASH_SALT PGBACKREST_REPO1_CIPHER_PASS TCRM_BACKUP_PASSPHRASE_FILE \
TCRM_COMPOSE_FILE TCRM_PGBACKREST_REPO_DIR; do
  grep -Eq "^${k}=." deploy/.env || echo "缺: $k"
done   # 期望无输出
```

## 9. 首次发布【主机 root】

前提：负责人在 main 打标签推送，Release 工作流构建四个镜像成功（README「发布与回滚」）。

```bash
cd /opt/tcrm
git fetch --tags && git checkout v<版本>
deploy/scripts/release.sh deploy <版本>
```

release.sh 从 `.env` 读 `TCRM_COMPOSE_FILE`（任务 150），无需行内传参。流程与 AWS 版相同：检查必填项 → 拉镜像解静态 → 首次跳过备份 → 起 postgres/redis → crm-admin 等健康 → crm-redirect → 切 current → 起 Nginx → 写回版本。

确认：

```bash
deploy/scripts/release.sh status
docker compose -f deploy/docker-compose.aliyun.yml --env-file deploy/.env ps   # 五服务 running，除 nginx 外 healthy
```

postgres 首启 1～2 分钟；第 10 章建 stanza 前 WAL 归档报错属预期窗口。

## 10. pgBackRest 初始化与第一次全量备份【主机 root】

只做一次。posix 仓库不需要凭证，`check` 验证本地归档链路：

```bash
cd /opt/tcrm
PB="docker compose -f deploy/docker-compose.aliyun.yml --env-file deploy/.env exec -u postgres postgres pgbackrest --stanza=tcrm"
$PB stanza-create
$PB check
deploy/backup/pgbackrest-backup.sh full
$PB info
```

确认：`info` 列出全量备份与 WAL 起点；`/var/lib/tcrm/pgbackrest-repo` 出现 `archive` 与 `backup` 目录。失败先看仓库目录属主是否 999、是否为空（stanza-create 要求空目录）。

## 11. 平台管理员初始化、删除引导密码【浏览器 + 主机 root】

与 AWS 版第 14 章相同：浏览器登录平台管理员 → 为默认公司建超级管理员 → 删 `.env` 的 `TCRM_BOOTSTRAP_ADMIN_PASSWORD` 行 → `docker compose -f deploy/docker-compose.aliyun.yml --env-file deploy/.env up -d --no-deps crm-admin`。

## 12. 定时任务与首次 ops-check【主机 root】

`crontab -e` 粘贴整块（UTC；顶部 `TCRM_COMPOSE_FILE` 行对全部任务生效；**没有**凭证刷新任务）：

```cron
# tcrm 阿里云生产定时任务（root，时间 UTC）
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TCRM_COMPOSE_FILE=/opt/tcrm/deploy/docker-compose.aliyun.yml

# 每 5 分钟：主机检查，告警走飞书 Webhook，结束发心跳
*/5 * * * * /opt/tcrm/deploy/scripts/ops-check.sh >> /var/log/tcrm-ops-check.log 2>&1
# 每天 02:00 UTC：pgBackRest 备份（周日全量、其余日差异），结束前 check 归档链路
0 2 * * * /opt/tcrm/deploy/backup/pgbackrest-backup.sh >> /var/log/tcrm-pgbackrest.log 2>&1
# 每天 03:00 UTC：gpg 加密逻辑备份，只留本机（保留 7 日 + 4 周）
0 3 * * * /opt/tcrm/deploy/backup/pg-backup.sh >> /var/log/tcrm-backup.log 2>&1
# 每天 04:17 UTC：admin./upload. 公开证书续期检查
17 4 * * * /opt/tcrm/deploy/scripts/renew-public-cert.sh >> /var/log/tcrm-cert.log 2>&1
```

首次验证：

```bash
crontab -l | grep -c '/opt/tcrm/deploy'   # 期望 4
cd /opt/tcrm
deploy/backup/pg-backup.sh                # 手动一次，验证 gpg；不上传
ls -t /var/lib/tcrm/backups/*.dump.gpg | head -1   # 期望列出刚产出的文件
deploy/scripts/ops-check.sh               # 期望 ops-check: all checks passed
```

告警链路演练（可在演练机做）：stop redis → 下轮 ops-check 飞书收到 ALERT → start redis 后收到 RECOVERED。

## 13. 上线检查清单

| # | 检查项 | 确认方式 | 期望 |
|---|---|---|---|
| 1 | 未开邮件链接日志 | `grep log-links /opt/tcrm/deploy/.env` | 无输出 |
| 2 | `upload.` 灰云 | Cloudflare DNS | DNS only |
| 3 | `upload.` 公开证书 | `openssl s_client -connect upload.<域名>:443 -servername upload.<域名> </dev/null \| openssl x509 -noout -issuer` | Let's Encrypt |
| 4 | `url.upload` 已设 | 系统配置或 `grep TCRM_UPLOAD_BASE_URL deploy/.env` | `https://upload.<域名>` |
| 5 | CORS 含管理端源 | `grep TCRM_CORS_ALLOWED_ORIGINS deploy/.env` | 含 `https://admin.<域名>` |
| 6 | 微店验证文件 | `curl -s https://<域名>/weidian_open.json` | 返回验证 JSON |
| 7 | 引导密码已删 | 第 11 章 | 已删且 healthy |
| 8 | 外部探测就绪 | UptimeRobot / Healthchecks | 三条探测 Up，心跳到达 |
| 9 | 邮件真实可达 | 新建达人发邀请 + 忘记密码 | 收到、链接可开 |
| 10 | 定时任务在跑 | 次日看 `/var/log/tcrm-*.log` | 当日条目无报错 |
| 11 | 快照已产出【控制台】 | 快照列表 | 当天系统盘快照存在 |
| 12 | 版本一致 | `release.sh status` | 版本、current、镜像 tag 一致 |
| 13 | 恢复演练完成 | 第 14 章 | 记录表勾完 |

## 14. 恢复与演练

**本地时间点恢复**（数据卷损坏、误操作；仓库与数据同盘，盘坏则走快照路径）：

```bash
cd /opt/tcrm
TCRM_COMPOSE_FILE=/opt/tcrm/deploy/docker-compose.aliyun.yml deploy/backup/restore-pitr.sh latest
# 或指定时间点：… restore-pitr.sh "2026-11-12 08:30:00"（UTC）
```

恢复后人工事项与 AWS 版相同（README「恢复」）：重建链接索引、核对钱包日志、补录恢复点后的人工操作、立即全量备份、重新登录。

**整机丢失**（快照路径，最多丢一天）：

1. 用最近系统盘快照创建新 ECS（同规格，安全组、元数据加固模式照第 2 章），或原实例更换系统盘 from 快照。
2. 快照含 Docker 卷与 posix 仓库：起栈 `docker compose -f deploy/docker-compose.aliyun.yml --env-file deploy/.env up -d`，核对数据与 `pgbackrest info`。
3. 公网 IP 变化时更新 Cloudflare 五条 A 记录（转了 EIP 则重新绑定）。
4. 立即做一次全量备份与逻辑备份；按第 13 章复核。

**演练**（上线前一次，此后每季度）：在独立 Compose 项目或另一台主机做，模板可复用 `deploy/drill/`（子网不得与 VPC 重叠；阿里云默认 VPC 网段在控制台核对，勿用 `172.31.0.0/24` 这类可能重叠的段）。记录恢复耗时（目标 ≤ 2 小时）、本地丢失窗口（约 5 分钟）、快照重建丢失窗口（≤ 1 天）、四条核对 SQL、Flyway 校验。

## 15. 日常运维入口

| 事项 | 入口 |
|---|---|
| 发布 / 回滚 / 状态 | README「发布与回滚」（release.sh 自动用 `.env` 的 compose 文件） |
| 告警处理 | README「监控告警」→「告警处理指引」 |
| 时间点恢复 | 本手册第 14 章；README「恢复」 |
| 证书续期排障 | README「公开证书的申请与续期」 |
| 容量与扩容 | README「资源与 Redis 容量」 |
| 接 OSS 恢复 5 分钟目标 | 分支 `task/149-aliyun-backup-creds`（搁置），启用时另立任务 |

README 正文以 AWS 为叙述对象，阿里云主机以本手册为准；两者冲突时本手册优先。