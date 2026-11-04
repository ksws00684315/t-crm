# 生产装机与上线手册（RUNBOOK）

> **用途**：在一台空白 EC2（Ubuntu 24.04 LTS）上从零逐条照做，直到上线检查全部通过。临时演练主机同样按本手册执行；演练中发现的问题记入[第 17 章演练记录表](#17-恢复演练与演练记录表)。
>
> - 本文只写**执行顺序、命令原文和确认方法**；原理与背景见 [`README.md`](README.md) 对应小节和 [ADR-037](../docs/06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md)。
> - 需要负责人在控制台完成的事项见[《生产上线：负责人操作清单》](../docs/04-开发任务/生产上线-负责人操作清单.md)（下称「操作清单」），本文只引用、不复制。
> - 已定前提（2026-11-04）：Ubuntu 24.04 x86_64 / us-west-2；Session Manager 登录、`sudo -i` 以 root 操作、不开 22 端口；仓库克隆到 `/opt/tcrm`、属主 root、按版本标签检出；定时任务全放 root 的 crontab；主机时区 UTC；证书、静态文件、备份、凭证、状态等数据目录全部在仓库之外、统一放 `/var/lib/tcrm/` 下；`.env.example` 的相对路径默认值保持不变，生产取值见第 8 章。
> - 本文由任务 108 按仓库现状**静态核对**编写，未在真机执行过；「待真机确认」项见第 17 章演练记录表。
> - 占位符：`<域名>`（如 `example.com`）、`<域名前缀>`（取域名点号前的部分，用于给桶起名）、`<版本>`（如 `1.0.0`，标签为 `v<版本>`）、`<主备份桶名>`、`<EIP>`、`<GitHub 令牌>`、`<Cloudflare API 令牌>`（后两个在第 5 章生成）。除标注【AWS 控制台】【Cloudflare】【负责人】【浏览器】外，其余步骤都在主机上以 root 执行。

## 0. 目录规划

| 路径 | 用途（对应 `.env` 变量） | 权限 |
|---|---|---|
| `/opt/tcrm` | 仓库（`git clone`，属主 root） | 755 |
| `/var/lib/tcrm` | 数据根目录。保持默认 755 即可：子目录以 bind 挂载进容器（路径由 Docker 守护进程以 root 解析），宿主机父目录权限不影响容器读取；**必须 755 的是 `pgbackrest-creds` 自身**（README「备份与恢复」） | 755 |
| `/var/lib/tcrm/certs` | 证书目录（`TCRM_CERT_DIR`）：`origin.pem`、`origin.key`、`public.pem`、`public.key` | 755，私钥 600 |
| `/var/lib/tcrm/www` | 前端静态目录（`TCRM_STATIC_DIR`）：`releases/<版本>/`、`current` 软链 | 755 |
| `/var/lib/tcrm/backups` | 加密逻辑备份（`TCRM_BACKUP_DIR`） | 700 |
| `/var/lib/tcrm/pgbackrest-creds` | pgBackRest S3 临时凭证（`TCRM_PGBACKREST_CREDS_DIR`）。**必须 755**，文件本身 600、属主 uid 999 | 755 |
| `/var/lib/tcrm/secrets` | 备份口令文件（`TCRM_BACKUP_PASSPHRASE_FILE`）与 Cloudflare API 令牌文件（`TCRM_CERTBOT_CLOUDFLARE_INI`）。README 中的 `/etc/tcrm/…` 是示例路径，本手册按已定前提统一放这里 | 700，文件 600 |
| `/var/lib/tcrm/ops-state` | 告警去重状态（`TCRM_OPS_STATE_DIR`） | 700 |
| `/var/lib/tcrm/certbot` | certbot 账号与证书状态（`TCRM_CERTBOT_STATE_DIR`，变量见 `scripts/renew-public-cert.sh` 文件头） | 700 |
| Docker 卷 `tcrm_pgdata`、`tcrm_redisdata` | 数据库与 Redis 数据（external 卷，第 7 章创建） | — |

## 1. 开机前决定与账号【负责人】

按操作清单 §1 完成：机型与磁盘（EC2 4 核 8G 非突发、系统盘 gp3 200GB 起、计费告警）、恢复目标（丢约 5 分钟 / 2 小时恢复）、`admin.` 是否走代理。账号用客户名下 AWS / Cloudflare / Resend 账号，根账号开 MFA、日常不用（操作清单开头）。密钥保管方式见操作清单 §5：`TCRM_SECRET_KEY`、`TCRM_IP_HASH_SALT`、两份备份口令、数据库与 Redis 密码、`.env` 全文进密码管理器，**与备份分开存放**。

## 2. AWS：S3 夡份桶与跨区域复制【AWS 控制台】

层次与理由见 README「备份与恢复」的备份层次表；这里只写操作。

1. **主备份桶**（us-west-2，名如 `<域名前缀>-tcrm-backup`）：
   - Block Public Access：保持全部阻止；
   - Properties → Bucket Versioning：**Enable**（跨区域复制的前提）；
   - Object Lock 不开（只开在副本桶）。
2. **副本桶**（us-east-2，名如 `<域名前缀>-tcrm-backup-replica`）：
   - 创建时 Advanced options → **Object Lock → Enable**（创建后无法补开）；版本控制随之开启；
   - Properties → Object Lock → Default retention：**35 天**；保留模式创建时选定（建议 COMPLIANCE，防勒索改删；此选择由负责人定，定后不可放宽）。
3. **跨区域复制**：主桶 Management → Replication → Create rule：Scope 选 All objects，目标选副本桶，让控制台自动创建 IAM 角色；Delete marker replication **不勾**（删除不传播）。
4. **生命周期规则**（主桶 Management → Lifecycle）：前缀 `logical/daily/` 到期 7 天；前缀 `logical/weekly/` 到期 28 天。`pgbackrest/` 前缀不加规则（保留期由 pgBackRest `repo1-retention-full=4` 管理）。
5. **确认**：两个桶的 Properties 页显示 Versioning enabled（副本桶另显示 Object Lock）；复制规则状态 Enabled。记下主桶名，第 8 章填 `.env`。

## 3. AWS：IAM 实例角色【AWS 控制台】

1. 建策略 `tcrm-backup-s3`：JSON 内容取仓库中的 `deploy/backup/iam-policy.example.json`（仓库是公开的，此刻主机还没克隆，直接在 GitHub 网页打开复制：<https://github.com/ksws00684315/t-crm> → `deploy/backup/iam-policy.example.json`），把 `EXAMPLE-TCRM-BACKUP-BUCKET` 全部替换为主备份桶名（含 `arn:aws:s3:::` 后的桶名和 `…/pgbackrest/*`、`…/logical/*` 资源）。
2. 建角色 `tcrm-prod-instance`：Trusted entity 选 EC2；附加两条策略：
   - `tcrm-backup-s3`（自定义，S3 备份读写）；
   - AWS 托管策略 **`AmazonSSMManagedInstanceCore`**（Session Manager 登录必需；IAM 示例与操作清单此前未列，操作清单已随本手册补上）。
3. 不创建任何长期访问密钥（ADR-037 决定 1）。
4. **确认**：角色详情页 Permissions 挂着上述两条策略；Trust relationships 为 `ec2.amazonaws.com`。

## 4. AWS：EC2、安全组与弹性 IP【AWS 控制台】

1. **安全组** `tcrm-prod-sg`（VPC 用默认）：入站只开 `80/tcp`、`443/tcp`，来源 `0.0.0.0/0` 和 `::/0`（`upload.` 灰云需要任意来源直连）；**不开 22**（Session Manager 登录，见前提）；出站保持默认全放行（SSM 与镜像拉取需要 443 出站）。防火墙口径见 README「AWS 主机与防火墙」。
2. **启动实例**：Name `tcrm-prod`；AMI 选 Ubuntu Server **24.04 LTS**（64-bit x86）；机型按操作清单 §1（如 `c7a.xlarge`）；Key pair 选 **Proceed without a key pair**；网络 us-west-2 默认 VPC；安全组选 `tcrm-prod-sg`；存储 gp3 200GB；Advanced details：
   - IAM instance profile：`tcrm-prod-instance`；
   - Metadata options：Metadata version 选 **V2 only**（IMDSv2 强制），Hop limit **1**（防容器读取实例凭证，ADR-037 决定 1）。漏设时也可启动后在 Actions → Instance settings → Modify instance metadata options 补设。
3. **Session Manager 登录**：实例状态 running、状态检查通过后，选中实例 → Connect → **Session Manager** 标签 → Connect。连不上时先等 1～2 分钟（SSM 代理注册），仍失败检查角色是否含 `AmazonSSMManagedInstanceCore`。进入后执行 `sudo -i`，此后都在 root shell 操作（提示符为 `#`）。
4. **弹性 IP**：Elastic IPs → Allocate；关联到本实例；记下 `<EIP>`（下一步 DNS 用）。注意 EIP 空闲也会计费。
5. 可选项见操作清单 §2：S3 网关终端节点、EBS 快照策略（Data Lifecycle Manager，每日一次保留 7 天）。

## 5. Cloudflare 与域名【Cloudflare，负责人】

操作清单 §3 有完整条目；此处按顺序列出与装机直接相关的动作。

1. 域名开注册商锁和 MFA（操作清单 §3 第 1 条）。
2. DNS 四条 A 记录都指向 `<EIP>`：`t.`、`kol.`、`admin.` **橙云（Proxied）**；`upload.` **灰云（DNS only）**（README「生产部署」第 1 条）。
3. SSL/TLS → Overview：模式 **Full (strict)**。
4. SSL/TLS → Origin Server → Create Certificate：默认参数（RSA、覆盖 `<域名>` 与 `*.<域名>`）→ 生成后**立即保存** PEM 与私钥（页面只显示一次），第 9 章放到主机。
5. **开启 Authenticated Origin Pulls**（SSL/TLS → Edge Certificates 或 Origin Pull 页）：不开则 `t.`、`kol.`、`admin.` 三个域名回源失败（Nginx 会校验 Cloudflare 客户端证书，见 README「Nginx 说明」）。
6. 创建 API 令牌：权限只给本 Zone 的 **Zone → DNS → Edit**（certbot DNS-01 用，第 8、9 章写入主机）。令牌值不进 `.env`、不进日志。
7. Security → WAF → Rate limiting rules：按仓库 README「Cloudflare 频率限制规则」表的规则表达式与阈值建管理端验证码签发规则（GitHub 网页可看），建好在 README 该表注明日期。
8. 其他服务（操作清单 §4 + README「邮件服务」）：Resend 添加发件域名（建议 `mail.<域名>`，与第 8 章 `TCRM_MAIL_FROM` 一致）并加 SPF/DKIM/DMARC 记录、建 API Key；UptimeRobot 三条探测；Healthchecks 建一个心跳检查（记下 URL）；飞书建自定义机器人（关键词放行 `ALERT` 与 `RECOVERED`、不开签名校验，记下 webhook URL）；GitHub 建 `read:packages` 令牌。

## 6. 主机初始化【主机 root】

### 6.1 系统更新与时区

```bash
apt-get update && apt-get -y upgrade
[ -f /var/run/reboot-required ] && reboot   # 升级了内核就重启，Session Manager 重连后继续

timedatectl set-timezone UTC
timedatectl
```

确认：`timedatectl` 输出 `Time zone: Etc/UTC (UTC, +0000)`、NTP service active。

### 6.2 Docker Engine 与 Compose 插件（官方 apt 源）

按 Docker 官方文档 Ubuntu 安装页（<https://docs.docker.com/engine/install/ubuntu/>）的 apt 仓库方式，以 root 执行：

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
```

确认：`docker version`、`docker compose version` 正常输出。

### 6.3 `/etc/docker/daemon.json`：live-restore

README「AWS 主机与防火墙」：升级或重启 Docker 守护进程时容器继续运行。

```bash
printf '{\n  "live-restore": true\n}\n' > /etc/docker/daemon.json
systemctl reload docker
docker info --format '{{.LiveRestoreEnabled}}'   # 期望 true
```

### 6.4 AWS CLI v2（官方安装包并校验签名）

`apt` 里的 `awscli` 是 v1，**不能满足** `refresh-s3-credentials.sh`（`aws configure export-credentials` 是 v2 子命令），必须用官方安装包。按 AWS CLI 官方文档（<https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html>）Linux x86_64 步骤：

```bash
apt-get install -y unzip gnupg   # 解压与签名校验
cd /tmp
curl -fsSL -o awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip
curl -fsSL -o awscliv2.zip.sig https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip.sig
```

导入 AWS CLI 公钥（内容照官方文档「Verify the integrity of your downloaded zip file」一节，Key ID `A6310ACC4672475C`，指纹 `FB5D B77F D5C1 18B8 0511  ADA8 A631 0ACC 4672 475C`；官方文档页可直接复制公钥文本）：

```bash
nano /tmp/aws-cli-public-key.asc   # 粘贴官方文档中的 -----BEGIN PGP PUBLIC KEY BLOCK----- … 全文
gpg --import /tmp/aws-cli-public-key.asc
gpg --verify awscliv2.zip.sig awscliv2.zip
```

确认：`gpg --verify` 输出 `Good signature from "AWS CLI Team <aws-cli@amazon.com>"`（后续的 "not certified with a trusted signature" 警告是预期）。然后安装并验证实例角色：

```bash
unzip -q awscliv2.zip
./aws/install                    # 安装到 /usr/local/aws-cli，命令链接到 /usr/local/bin/aws
aws --version                    # 期望 aws-cli/2.x
aws sts get-caller-identity      # 期望 Arn 含 assumed-role/tcrm-prod-instance/…
```

### 6.5 命令齐套核对

主机上的脚本（`release.sh`、`ops-check.sh`、`renew-public-cert.sh`、`backup/` 四个脚本）用到的外部命令：`docker`（含 compose 插件）、`gpg`、`curl`、`openssl`、`aws`，以及 Ubuntu 自带的 coreutils/diffutils/findutils/gawk/sed（`awk` `grep` `sed` `find` `date` `stat` `install` `cmp` `readlink` `mktemp` 等）。另有两条是手册步骤（不是脚本）需要的：`git`（第 7 章克隆仓库、第 12 章检出标签）和 `crontab`（第 15 章，cron 服务 Ubuntu 自带）。`git` 需要安装，其余随系统或前几步带入：

```bash
apt-get install -y git
for c in git docker gpg curl openssl aws crontab awk grep sed find date stat install cmp readlink mktemp; do
  command -v "$c" > /dev/null || echo "缺少：$c"
done   # 期望无输出
docker compose version
```

## 7. 获取代码、目录与数据卷【主机 root】

### 7.1 克隆仓库并检出标签

```bash
git clone https://github.com/ksws00684315/t-crm.git /opt/tcrm
cd /opt/tcrm
git fetch --tags
git checkout v<版本>            # 例如 v1.0.0；Compose 文件、Nginx 配置、脚本都来自这里的标签
```

确认：`git -C /opt/tcrm describe --tags` 显示目标标签。发布标签由负责人按 README「发布与回滚」在 main 上打好并等 Release 工作流成功；演练可用临时标签（如 `v0.1.0`），用完在 GitHub 删除。

### 7.2 数据目录

```bash
install -d -m 755 /var/lib/tcrm                 # 保持默认 755 即可（见第 0 章说明）
install -d -m 755 /var/lib/tcrm/certs
install -d -m 755 /var/lib/tcrm/www
install -d -m 700 /var/lib/tcrm/backups
install -d -m 700 /var/lib/tcrm/secrets
install -d -m 755 /var/lib/tcrm/pgbackrest-creds   # 必须 755（README「备份与恢复」首次启用顺序）
install -d -m 700 /var/lib/tcrm/ops-state
install -d -m 700 /var/lib/tcrm/certbot
```

确认：`stat -c '%a' /var/lib/tcrm /var/lib/tcrm/pgbackrest-creds` 都输出 `755`。

### 7.3 外部数据卷（只做一次）

```bash
docker volume create tcrm_pgdata
docker volume create tcrm_redisdata
docker volume ls --format '{{.Name}}'   # 期望列出 tcrm_pgdata、tcrm_redisdata
```

这两个卷在 Compose 里是 `external`，`down -v` 也不会删（README「生产部署」第 4 条）；没建卷时发布会报找不到卷。

### 7.4 GHCR 登录

镜像为私有，用第 5 章建的 `read:packages` 令牌登录一次（令牌不进仓库和 `.env`，README「生产部署」第 3 条）：

```bash
echo <GitHub 令牌> | docker login ghcr.io -u ksws00684315 --password-stdin
grep -c ghcr.io /root/.docker/config.json   # 期望 ≥ 1（凭证已保存）
```

## 8. 填写 `deploy/.env`【主机 root】

### 8.1 两个口令/令牌文件

```bash
install -m 600 /dev/null /var/lib/tcrm/secrets/backup.pass
openssl rand -base64 32 > /var/lib/tcrm/secrets/backup.pass          # pg_dump 加密口令
install -m 600 /dev/null /var/lib/tcrm/secrets/cloudflare.ini
printf 'dns_cloudflare_api_token = <Cloudflare API 令牌>\n' > /var/lib/tcrm/secrets/cloudflare.ini
stat -c '%a' /var/lib/tcrm/secrets/*   # 期望都是 600
```

两个口令另抄进密码管理器（操作清单 §5）；`backup.pass` 内容丢失则所有 `.dump.gpg` 无法解密。

### 8.2 `.env` 逐项

```bash
cp /opt/tcrm/deploy/.env.example /opt/tcrm/deploy/.env
chmod 600 /opt/tcrm/deploy/.env
nano /opt/tcrm/deploy/.env
```

生产取值（变量名与 `deploy/.env.example` 一致；「留默认」表示不改动示例值）：

| 变量 | 生产取值 | 说明 / 生成方式 |
|---|---|---|
| `TCRM_IMAGE_REGISTRY` | 留默认 `ghcr.io/ksws00684315` | 小写、无末尾斜杠（`release.sh` 检查） |
| `TCRM_VERSION` | **`<版本>`（与首次发布的标签一致，如 `1.0.0`）** | 首次发布会把 `crm-postgres` 固定到该值并预拉取；保留 `.env.example` 占位 `1.0.0` 而首发其他版本会拉取不存在的数据库镜像、发布中止（README「生产部署」第 3 条） |
| `TCRM_POSTGRES_VERSION` | 留空 | 首次发布后由 `release.sh` 写入固定值，此后日常发布不再改动数据库镜像 |
| `TCRM_DOMAIN` | `<域名>` | |
| `TCRM_CERT_DIR` | `/var/lib/tcrm/certs` | 挂载进 Nginx（只读） |
| `TCRM_CERTBOT_CLOUDFLARE_INI` | `/var/lib/tcrm/secrets/cloudflare.ini` | 权限必须 600，脚本检查 |
| `TCRM_CERTBOT_EMAIL` | 负责人邮箱 | Let's Encrypt 到期提醒 |
| `TCRM_CERTBOT_STATE_DIR` | `/var/lib/tcrm/certbot` | 可选变量（脚本文件头说明，`.env.example` 未列）；默认在仓库内，按前提放仓库外 |
| `TCRM_STATIC_DIR` | `/var/lib/tcrm/www` | |
| `TCRM_BACKUP_DIR` | `/var/lib/tcrm/backups` | |
| `TCRM_BACKUP_S3_BUCKET` | `<主备份桶名>` | 逻辑备份上传目标（第 2 章创建） |
| `TCRM_BACKUP_S3_PREFIX` | 留默认 `logical` | |
| `AWS_REGION` | 留默认 `us-west-2` | |
| `TCRM_BACKUP_PASSPHRASE_FILE` | `/var/lib/tcrm/secrets/backup.pass` | 8.1 创建 |
| `PGBACKREST_REPO1_TYPE` | 留默认 `s3` | |
| `PGBACKREST_REPO1_PATH` | 留默认 `/pgbackrest` | S3 内前缀 |
| `PGBACKREST_REPO1_S3_BUCKET` | `<主备份桶名>` | |
| `PGBACKREST_REPO1_S3_REGION` | 留默认 `us-west-2` | |
| `PGBACKREST_REPO1_S3_ENDPOINT` | 保持注释 | 默认 `s3.us-west-2.amazonaws.com` |
| `PGBACKREST_REPO1_CIPHER_PASS` | `openssl rand -base64 32` | 丢失则 pgBackRest 备份全部无法读取，密码管理器另存 |
| `TCRM_PGBACKREST_CREDS_DIR` | 留默认 `/var/lib/tcrm/pgbackrest-creds` | |
| `TCRM_DB_NAME` | 留默认 `tcrm` | |
| `TCRM_DB_USERNAME` | 如 `tcrm_app` | 应用账号（非超级用户，首次初始化数据卷时创建），与下面的超级用户**不同名** |
| `TCRM_DB_PASSWORD` | `openssl rand -base64 24` | |
| `TCRM_DB_SUPERUSER_USERNAME` | 如 `tcrm_admin` | 仅管理与备份用，不发给应用 |
| `TCRM_DB_SUPERUSER_PASSWORD` | `openssl rand -base64 24` | 与上一条不同 |
| `TCRM_REDIS_PASSWORD` | `openssl rand -base64 24` | 生产必填，Compose 检查 |
| `TCRM_BOOTSTRAP_ADMIN_USERNAME` | 如 `platform_admin` | 首次启动创建平台管理员；第 14 章登录后只删密码行 |
| `TCRM_BOOTSTRAP_ADMIN_PASSWORD` | 强密码（密码管理器） | 同上 |
| `TCRM_CORS_ALLOWED_ORIGINS` | `https://admin.<域名>` | 跨域上传预检需要（README「生产部署」第 8 条） |
| `TCRM_LINK_BASE_URL` | `https://t.<域名>` | https、无末尾斜杠 |
| `TCRM_KOL_PORTAL_BASE_URL` | `https://kol.<域名>` | 同上 |
| `TCRM_ADMIN_BASE_URL` | `https://admin.<域名>` | 微店 OAuth 回调域名 |
| `TCRM_SECRET_KEY` | `openssl rand -base64 32` | 32 字节 Base64；丢失则已存凭证全部重录 |
| `TCRM_MAIL_API_KEY` | Resend API Key（`re_…`） | 为空则邮件只写日志，生产不可 |
| `TCRM_MAIL_FROM` | `SparkTrack <no-reply@mail.<域名>>` | 域名须已在 Resend 验证 |
| `TCRM_REDIRECT_FALLBACK_URL` | `https://kol.<域名>`（或客户指定落地页） | 中转链接查不到索引时的跳转 |
| `TCRM_IP_HASH_SALT` | `openssl rand -hex 32` | 至少 32 字符 |
| `TCRM_UPLOAD_BASE_URL` | `https://upload.<域名>` | 系统配置 `url.upload` 的默认值 |
| `TCRM_ALERT_WEBHOOK_URL` | 飞书机器人 webhook | 为空则告警只打印 |
| `TCRM_HEARTBEAT_URL` | Healthchecks 分配的 URL | 为空则不发心跳 |
| `TCRM_OPS_STATE_DIR` | `/var/lib/tcrm/ops-state` | 默认在仓库内，按前提放仓库外 |
| `TCRM_OPS_*` 阈值 | 保持注释 | 生产用默认值 |
| 端口、`TCRM_DB_HOST`、`TCRM_REDIS_HOST`、`TCRM_TRUSTED_PROXIES` | 留默认/留空 | 生产 Compose 写死为服务名/网段，不读这些值（`.env.example` 注释） |
| `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS`、`TCRM_CLIENT_IP_HEADERS`、`TCRM_CLICK_STREAM_MAXLEN` | 留空/保持注释 | 生产 Compose **会**把它们传入容器，留空即用容器内默认值；Flyway 项只在应急回滚时按 README「发布与回滚」第 9 项临时设置，事后删除 |

**确认**：`stat -c '%a' /opt/tcrm/deploy/.env` 输出 `600`；`.env` 不含 `crm.mail.log-links`。

## 9. 证书【主机 root + Cloudflare】

Nginx 启动要求四个文件齐全（`release.sh` 检查 `origin.pem`、`origin.key`、`public.pem`、`public.key`）。

### 9.1 放置 Cloudflare 源站证书（覆盖 `t.`、`kol.`）

第 5 章保存的 PEM 与私钥写入：

```bash
nano /var/lib/tcrm/certs/origin.pem   # 粘贴证书 PEM
nano /var/lib/tcrm/certs/origin.key   # 粘贴私钥
chmod 600 /var/lib/tcrm/certs/origin.key
```

### 9.2 首次申请 Let's Encrypt 公开证书（`admin.`、`upload.`）

在首次发布**之前**执行（README「公开证书的申请与续期」；续期由第 15 章 crontab 负责）：

```bash
cd /opt/tcrm
deploy/scripts/renew-public-cert.sh
```

确认：`ls -l /var/lib/tcrm/certs` 出现 `public.pem` 与 `public.key`（600）；签发者与域名：

```bash
openssl x509 -noout -issuer -subject -ext subjectAltName -in /var/lib/tcrm/certs/public.pem
# 期望 issuer 含 Let's Encrypt；SAN 含 admin.<域名> 与 upload.<域名>
```

## 10. 首次刷新备份凭证【主机 root】

必须在**启动 postgres 之前**执行（README「备份与恢复」首次启用顺序）。注意：该脚本只读环境变量、**不读 `deploy/.env`**，所以要行内传入目录变量（crontab 同理，见第 15 章）：

```bash
TCRM_PGBACKREST_CREDS_DIR=/var/lib/tcrm/pgbackrest-creds /opt/tcrm/deploy/backup/refresh-s3-credentials.sh
stat -c '%u %a' /var/lib/tcrm/pgbackrest-creds/s3-credentials.conf   # 期望 999 600
```

确认：输出 `credentials written to …`；文件属主 999、权限 600。**不要 `cat` 该文件**（内含临时凭证）。失败时看 stderr 输出（脚本不回显凭证内容），常见原因是实例角色未挂好。

## 11. 上线前核对（人工核对表）

在首次发布前逐项执行；标「终检」的一项（第 21 项）在第 15 章之后补勾。

| # | 核对项 | 确认命令 / 位置 | 期望 |
|---|---|---|---|
| 1 | 架构 x86_64 | `uname -m` | `x86_64` |
| 2 | 时区 UTC | `timedatectl` | `Time zone: Etc/UTC (UTC, +0000)` |
| 3 | Docker / Compose | `docker version --format '{{.Server.Version}}'`；`docker compose version` | 正常输出版本号 |
| 4 | live-restore 已开启 | `docker info --format '{{.LiveRestoreEnabled}}'` | `true` |
| 5 | AWS CLI 是 v2 | `aws --version` | `aws-cli/2…` |
| 6 | 实例角色生效 | `aws sts get-caller-identity` | `Arn` 含 `assumed-role/tcrm-prod-instance/…` |
| 7 | 无长期密钥文件 | `ls /root/.aws/credentials` | No such file or directory |
| 8 | 无密钥环境变量 | `env \| grep -E '^AWS_(ACCESS_KEY_ID\|SECRET_ACCESS_KEY)'` | 无输出 |
| 9 | `.env` 权限 600 | `stat -c '%a' /opt/tcrm/deploy/.env` | `600` |
| 10 | `release.sh` 必填项全有值 | 见下方循环命令 | 无 `缺:` 输出 |
| 11 | `TCRM_VERSION` 与首发版本一致 | `grep '^TCRM_VERSION=' /opt/tcrm/deploy/.env` | 等于本次发布的 `<版本>` |
| 12 | 外部数据卷存在 | `docker volume ls --format '{{.Name}}' \| grep -c '^tcrm_'` | `2` |
| 13 | 证书文件齐全 | `ls /var/lib/tcrm/certs` | 四个文件都在 |
| 14 | 私钥权限 600 | `stat -c '%a' /var/lib/tcrm/certs/origin.key /var/lib/tcrm/certs/public.key` | 都是 `600` |
| 15 | 备份口令文件 600 | `stat -c '%a' /var/lib/tcrm/secrets/backup.pass` | `600` |
| 16 | 凭证目录 755 | `stat -c '%a' /var/lib/tcrm/pgbackrest-creds /var/lib/tcrm` | 都是 `755` |
| 17 | 凭证文件属主/权限 | `stat -c '%u %a' /var/lib/tcrm/pgbackrest-creds/s3-credentials.conf` | `999 600` |
| 18 | 只有 Nginx 发布端口 | `cd /opt/tcrm && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env config \| grep -c 'published:'` | `2`（仅 Nginx 的 80/443） |
| 19 | 数据盘余量满足预留 | `df -h /var/lib/docker` | Avail ≥ 约 8 GiB（README「S3 不可用时」：4 GiB 归档队列 + 1 GiB `max_wal_size` + 突发余量；不要只按 4+1 估算） |
| 20 | IMDSv2 强制、hop limit 1【AWS 控制台】 | 实例 Details → Instance metadata options | `IMDSv2 required`、`Hop limit: 1`（读取该值需额外 IAM 权限，故在控制台核对，不用主机 CLI） |
| 21 | crontab 已安装（终检） | `crontab -l \| grep -c '/opt/tcrm/deploy'` | ≥ 5 |

第 10 项循环命令（变量清单即 `release.sh` 的 `REQUIRED_ENV`）：

```bash
cd /opt/tcrm
for k in TCRM_IMAGE_REGISTRY TCRM_DOMAIN TCRM_DB_USERNAME TCRM_DB_PASSWORD \
TCRM_DB_SUPERUSER_USERNAME TCRM_DB_SUPERUSER_PASSWORD TCRM_REDIS_PASSWORD \
TCRM_LINK_BASE_URL TCRM_KOL_PORTAL_BASE_URL TCRM_ADMIN_BASE_URL TCRM_REDIRECT_FALLBACK_URL \
TCRM_SECRET_KEY TCRM_IP_HASH_SALT PGBACKREST_REPO1_S3_BUCKET PGBACKREST_REPO1_CIPHER_PASS \
TCRM_PGBACKREST_CREDS_DIR TCRM_BACKUP_PASSPHRASE_FILE; do
  grep -Eq "^${k}=." deploy/.env || echo "缺: $k"
done   # 期望无输出
```

## 12. 首次发布【主机 root】

前提（负责人在本机做，第 7 章已检出同一标签）：在 `main` 上打版本标签并推送——`git tag v<版本> && git push origin v<版本>`——GitHub Actions 的 Release 工作流会构建并推送四个镜像到 GHCR：`crm-admin`、`crm-redirect`、`crm-postgres`、`crm-static`。等 Actions 页面的 Release 工作流成功（README「发布与回滚」；标签必须指向已在 main 上通过检查的提交）。演练同样走这一步，用临时标签即可。

```bash
cd /opt/tcrm
git fetch --tags && git checkout v<版本>
deploy/scripts/release.sh deploy <版本>
```

脚本依次：检查 `.env` 必填项 → 拉取镜像、解出静态文件 → 跳过备份（首次没有数据库）→ 启动 postgres、redis → 更新 crm-admin 并等健康 → crm-redirect → 切换 `current` → 启动 Nginx（此时检查四个证书文件）→ 写回 `TCRM_VERSION`。任一步失败会打印当前状态和下一步建议，不自动回滚。

**确认**：

```bash
deploy/scripts/release.sh status
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env ps   # 五个服务 running；除 nginx 外均 healthy（nginx 无健康检查）
```

- postgres 首次初始化需 1～2 分钟；全新数据卷首启若退出一次后自愈属预期（L-061，见第 17 章）；
- crm-admin 首启执行 Flyway 迁移，脚本默认最多等 300 秒（`TCRM_RELEASE_HEALTH_TIMEOUT` 可调大）；
- 第 13 章建 stanza 之前 WAL 归档会报错，属预期窗口；
- 公网验证（经 Cloudflare）：`https://t.<域名>/healthz` 200、`https://admin.<域名>` 200/302、`https://kol.<域名>` 200。直连 `<EIP>` 访问 `t.`/`admin.` 会被回源认证拒绝（400），这是预期防护。

## 13. pgBackRest 初始化与第一次全量备份【主机 root】

只做一次（README「备份与恢复」第 3 步）：

```bash
cd /opt/tcrm
PB="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec -u postgres postgres pgbackrest --stanza=tcrm"
$PB stanza-create
$PB check                                  # 真实向 S3 推取 WAL，验证凭证与桶
deploy/backup/pgbackrest-backup.sh full
$PB info                                   # 查看备份集和 WAL 范围
```

**确认**：`stanza-create`、`check` 无报错；`info` 列出全量备份（`full timestamp …`）且 WAL 起点在列。`check`/备份失败先看：凭证文件属主是否 999（第 11 章核对表第 17 项）、桶名与区域、实例角色策略前缀。

## 14. 平台管理员初始化、删除引导密码【浏览器 + 主机 root】

1. 浏览器打开 `https://admin.<域名>`，用 `TCRM_BOOTSTRAP_ADMIN_USERNAME` / `TCRM_BOOTSTRAP_ADMIN_PASSWORD` 登录平台管理员。
2. 在平台管理里为默认公司创建超级管理员；退出，用超级管理员登录确认可用。系统有两级管理员：平台管理员（本步登录的这个，首次启动用引导变量创建）与公司超级管理员（日常运营用）；「默认公司」是首次初始化时系统自动创建的（README「生产部署」第 2、11 条）。
3. 删除 `.env` 中的引导密码并让配置生效（务必 `--no-deps`）：

```bash
nano /opt/tcrm/deploy/.env                 # 删除 TCRM_BOOTSTRAP_ADMIN_PASSWORD=… 一行（用户名可保留）
cd /opt/tcrm && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --no-deps crm-admin
```

**确认**：`grep '^TCRM_BOOTSTRAP_ADMIN_PASSWORD' /opt/tcrm/deploy/.env` 无输出；crm-admin 重建后回到 healthy（约 30～60 秒管理端接口中断，属预期）。

## 15. 定时任务与首次 `ops-check`【主机 root】

`crontab -e` 粘贴以下整块（时间为主机时钟，已设为 UTC；`PATH` 行必须保留，AWS CLI 装在 `/usr/local/bin`；刷新脚本不读 `.env`，变量行内传入）：

```cron
# tcrm 生产定时任务（root，时间 UTC）
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# 每 15 分钟：取实例角色临时凭证写入 pgBackRest 凭证目录（postgres 容器读不到实例元数据，L-107）
*/15 * * * * TCRM_PGBACKREST_CREDS_DIR=/var/lib/tcrm/pgbackrest-creds /opt/tcrm/deploy/backup/refresh-s3-credentials.sh >> /var/log/tcrm-pgbackrest-creds.log 2>&1
# 每 5 分钟：主机检查（磁盘/内存/容器/证书/备份/凭证/日志），告警走飞书 Webhook，结束发心跳
*/5 * * * * /opt/tcrm/deploy/scripts/ops-check.sh >> /var/log/tcrm-ops-check.log 2>&1
# 每天 02:00 UTC：pgBackRest 备份（周日全量、其余日差异），结束前 check 归档链路
0 2 * * * /opt/tcrm/deploy/backup/pgbackrest-backup.sh >> /var/log/tcrm-pgbackrest.log 2>&1
# 每天 03:00 UTC：gpg 加密逻辑备份并上传 S3（保留 7 日 + 4 周）
0 3 * * * /opt/tcrm/deploy/backup/pg-backup.sh >> /var/log/tcrm-backup.log 2>&1
# 每天 04:17 UTC：admin./upload. 公开证书续期检查（距到期不足 30 天才真正续期）
17 4 * * * /opt/tcrm/deploy/scripts/renew-public-cert.sh >> /var/log/tcrm-cert.log 2>&1
```

首次验证：

```bash
crontab -l | grep -c '/opt/tcrm/deploy'          # 期望 5
cd /opt/tcrm
deploy/backup/pg-backup.sh                       # 手动跑一次逻辑备份，验证 gpg 与 S3 上传（IAM 前缀）
aws s3 ls --region us-west-2 s3://<主备份桶名>/logical/daily/ | tail -1   # 期望列出刚上传的 .dump.gpg
deploy/scripts/ops-check.sh                      # 手动跑一次；期望输出 ops-check: all checks passed
```

- **心跳到达**：Healthchecks 控制台看到刚产生的 ping。
- **告警到达**（演练机上做，站点会短暂不可用）：`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env stop redis` → 手动再跑一次 `ops-check.sh`（或等下一轮 cron）→ 飞书收到 `ALERT container: redis…`；`docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env start redis` 后下一轮收到 `RECOVERED container`。
- 发版重启会产生一对「5 分钟内重启过」告警与恢复通知，属预期（README「监控告警」）。

## 16. 上线检查清单

把 README「生产部署」第 7、8 条、操作清单 §6 等分散检查合成一张表；全部通过即视为上线完成。

| # | 检查项 | 确认方式 | 期望 |
|---|---|---|---|
| 1 | 生产未开邮件链接日志 | `grep log-links /opt/tcrm/deploy/.env` | 无输出（默认关闭，生产禁止开） |
| 2 | `upload.` DNS 为「仅 DNS」 | Cloudflare → DNS | `upload.<域名>` 记录灰色 DNS only |
| 3 | `upload.` 用公开证书 | `openssl s_client -connect upload.<域名>:443 -servername upload.<域名> </dev/null \| openssl x509 -noout -issuer` | issuer 为 Let's Encrypt（README「生产部署」第 8 条） |
| 4 | `url.upload` 已设置 | 管理端（平台管理员）→ 系统配置；或 `grep '^TCRM_UPLOAD_BASE_URL=' /opt/tcrm/deploy/.env` | `https://upload.<域名>` |
| 5 | CORS 包含管理端源 | `grep '^TCRM_CORS_ALLOWED_ORIGINS=' /opt/tcrm/deploy/.env` | 含 `https://admin.<域名>` |
| 6 | 微店回调域名已登记 | 微店开放平台控制台（操作清单 §3） | `admin.<域名>` 已登记 |
| 7 | 引导密码已删 | 第 14 章第 3 步 | 已删且 crm-admin healthy |
| 8 | 外部探测就绪 | UptimeRobot / Healthchecks 控制台（操作清单 §4） | `t./healthz`、管理端、达人端三条探测 Up；心跳按期到达 |
| 9 | 告警链路可达 | 第 15 章实测（演练机上做即可，不必在生产机 stop redis） | 飞书收到 ALERT 与 RECOVERED |
| 10 | 邮件真实可达 | 管理端「新建达人」发邀请邮件到真实邮箱，再走一遍「忘记密码」（README「邮件服务」） | 收到邮件、链接可打开 |
| 11 | 定时任务在跑 | `grep . /var/log/tcrm-*.log \| tail -5`（次日再看一次） | 各日志有当日条目且无报错 |
| 12 | 备份持续产出 | `deploy/scripts/ops-check.sh` 手动跑 | `all checks passed`（首轮备份过旧/缺失会告警） |
| 13 | 安全组只开 80/443 | AWS 控制台 `tcrm-prod-sg` Inbound rules | 仅两条（80、443），无 22 |
| 14 | 弹性 IP 已绑定 | AWS 控制台 Elastic IPs | 关联本实例 |
| 15 | 版本一致 | `deploy/scripts/release.sh status` | `TCRM_VERSION`、`current`、镜像 tag 都是 `<版本>` |
| 16 | 恢复演练完成 | 第 17 章 | 演练记录表全部勾完 |

## 17. 恢复演练与演练记录表

步骤与核对 SQL 见 README「恢复演练」（在**另一台**主机或独立 Compose 项目上做：准备相同 `.env`、空数据卷 → `deploy/backup/restore-pitr.sh latest` → 再试指定时间点 → 逻辑备份恢复 → 四条核对 SQL + Flyway 校验 + 重建链接索引 → 记录）。整机重建路径见 README「恢复」的「整机丢失时」一段。

演练记录表（演练时逐行填写。**第 1～9 行与第 17 行是「待真机确认」项**：本手册只做过静态核对，未在真机执行，必须在演练主机上验证；第 10～16 行是恢复演练本身的记录项）：

| # | 项目 | 对照 / 说明 | 结果（实测填写） |
|---|---|---|---|
| 1 | 各条安装命令照抄可执行 | 第 6～10 章每条命令 | |
| 2 | Session Manager 登录 | 角色含 `AmazonSSMManagedInstanceCore` 后可 Connect | |
| 3 | 实例角色凭证链 | `aws sts get-caller-identity` 返回 assumed-role | |
| 4 | IMDS hop limit 1 + 容器读不到元数据 | 控制台显示 1；容器内取不到实例凭证（L-107 的前提） | |
| 5 | 真实 S3 全链路 | `stanza-create`/`check`/全量/恢复均通 | |
| 6 | Let's Encrypt 真实签发与续期 | 第 9.2 节签发；续期 cron 次日输出 `certificate unchanged, nothing to reload` | |
| 7 | Cloudflare 回源认证生效 | 直连 `<EIP>`（带 `t.`/`admin.` Host）返回 400；经 Cloudflare 正常 | |
| 8 | L-061：全新数据卷首启 postgres 是否自愈 | 第 12 章发布时观察是否「退出一次→自动重启→健康」 | |
| 9 | L-110：Linux 上 `current` 软链切换无需刷新 | 首次发布与第二次发布后浏览器看到的都是新版本前端 | |
| 10 | 恢复总耗时 | 对照 ≤ 2 小时（从开始恢复到后台可登录） | |
| 11 | 实际丢失数据窗口 | 对照 ≤ 约 5 分钟 | |
| 12 | 核对项与生产一致 | README「恢复演练」第 4 步四条 SQL、Flyway 校验、链接跳转、钱包核对日志 | |
| 13 | 逻辑备份可解密恢复 | README「恢复演练」第 3 步 | |
| 14 | S3 跨区域复制实际效果 | 副本桶（us-east-2）出现新备份对象 | |
| 15 | Object Lock 实际效果 | 副本桶中尝试删除对象版本被拒绝 / 仅加删除标记 | |
| 16 | 告警与心跳真实到达 | 飞书与 Healthchecks | |
| 17 | 真实 `admin.`/`upload.` 证书浏览器上传大表格 | 操作清单 §6 第 3 条 | |

## 18. 日常运维入口（只放链接）

| 事项 | 入口 |
|---|---|
| 发布新版本 | README「发布与回滚」→「发布一个版本」 |
| 回滚 | README「发布与回滚」→「回滚」（含 Flyway 严格模式应急方法） |
| 状态查看 | `deploy/scripts/release.sh status`（README「发布与回滚」） |
| 告警处理 | README「监控告警」→「告警处理指引」 |
| 时间点恢复 / 逻辑备份恢复 | README「恢复」 |
| 证书续期排障 | README「公开证书的申请与续期」（日志 `/var/log/tcrm-cert.log`） |
| S3 不可用时 | README「备份与恢复」→「S3 不可用时」 |
| 容量与扩容 | README「资源与 Redis 容量」 |
