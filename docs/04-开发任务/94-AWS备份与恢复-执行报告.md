# 任务 94 执行报告：AWS 备份与恢复

> 依据：[`94-AWS备份与恢复-Codex提示词.md`](94-AWS备份与恢复-Codex提示词.md)、ADR-037 决定 5、6。分支 `task/94-aws-backup`（从 `main` 头提交 `7148f64e` 检出）。执行日期 2026-11-02（验证时间戳为 UTC，2026-11-01 23:35～23:57）。

## 1. 结论

§2 全部要求已实现，§4 七项验证全部在本机真实运行并通过。S3 真实连通按约定列入「待上线验证」，没有在本机验证。

验证中发现并修复一个会让数据库反复重启的问题：异步归档时，pgBackRest 的后台推送进程被托管给容器的 1 号进程，而 1 号进程是 postmaster 时，推送失败（例如 S3 不可用）的非零退出码会被当成后台进程崩溃，整个数据库重启。生产模板 postgres 服务因此增加了 `init: true`（见 §4 第 6 项）。这不属于提示词 §6「无法做到 S3 故障不写满磁盘」，加上 `init: true` 后该项验证通过，故没有停下来报告，在这里如实说明。

## 2. 版本与环境

| 项 | 值 |
|---|---|
| PostgreSQL 镜像 | `postgres:17.11`（Debian 13 trixie），新增 `deploy/postgres/Dockerfile` |
| pgBackRest | `2.60.0-1.pgdg13+1`（PGDG 软件源，官方镜像已配置；`apt-cache madison` 确认可取；MIT 许可，ADR-037 已批准） |
| 验证环境 | 独立 Compose 项目 `tcrm-t94-verify`（端口 19480 映射 crm-admin；Nginx 未启动，不需要证书），验证用 override 与环境文件在工作树 `.tmp/verify/`（已被 Git 忽略，不提交）；仓库类型 `posix`，仓库放独立卷 |
| 后台 | 用本工作树 `backend/Dockerfile` 构建的镜像，含 91 个迁移 |
| 主机 | macOS（arm64），Docker 29.9.2 / Compose 5.6.1；主机没有 gpg、aws、GNU date、shellcheck |
| 没碰的 | 共享中间件（55432、46391）、`tcrm-local`、`tcrm-preview`、负责人的 18081 等端口 |

主机缺 gpg、aws、GNU date，验证逻辑备份时在 `.tmp/shim/` 放了三个替身：`gpg` 转到 `postgres` 镜像里的真实 gpg 2.4.7 执行，`date` 转到 `ubuntu:24.04`，`aws` 只把文件复制到本地目录并记录命令行。所以加密、解密、保留规则是真实运行，S3 上传只验证了命令行形态。

## 3. 主要改动

| 文件 | 改动 |
|---|---|
| `deploy/postgres/Dockerfile`（新） | `FROM postgres:17.11`，安装写死版本的 pgBackRest，建好 spool、日志、posix 仓库目录并交给 postgres 用户 |
| `deploy/postgres/pgbackrest.conf`（新） | stanza `tcrm`；`repo1-cipher-type=aes-256-cbc`；`repo1-retention-full=4`；`archive-async=y`；`archive-push-queue-max=4GiB`；`start-fast=y`；归档推送日志降到 warn。无任何密钥 |
| `deploy/docker-compose.prod.yml` | postgres 服务改 `build:`（镜像名 `tcrm-postgres:17.11`）；`command` 增加 `archive_mode=on`、`archive_timeout=300`、`archive_command=pgbackrest --stanza=tcrm archive-push %p`；`PGBACKREST_*` 环境变量；`init: true` |
| `deploy/.env.example` | 去掉 `TCRM_R2_*`；新增 `TCRM_BACKUP_S3_BUCKET`、`TCRM_BACKUP_S3_PREFIX`、`AWS_REGION`、`TCRM_BACKUP_PASSPHRASE_FILE` 和 `PGBACKREST_*`（访问密钥与实例角色两种方式） |
| `deploy/backup/pgbackrest-backup.sh`（新） | 周日全量、其余日差异，结束前 `pgbackrest check`，失败非零退出；头部有 cron 示例和首次 `stanza-create` 方法 |
| `deploy/backup/pg-backup.sh` | S3 取代 R2；`pg_dump` 校验后 `gpg --symmetric --cipher-algo AES256` 加密，口令来自 600 权限文件；只保留 `.dump.gpg`；明文 `.partial` 由 `trap` 在任何退出路径删除；`expired_backups` 改匹配 `.dump.gpg`；头部写明解密恢复命令 |
| `deploy/backup/test-retention.sh` | 文件名随之改为 `.dump.gpg` |
| `deploy/backup/restore-pitr.sh`（新） | `<时间点\|latest>`，`--overwrite`，输入 `yes`，停应用与 postgres，一次性容器 `pgbackrest restore`，等待恢复结束，启动应用，打印人工事项 |
| `deploy/backup/iam-policy.example.json`（新） | 备份写入身份的最小权限，显式拒绝改生命周期、Object Lock、复制、版本控制、桶策略和删除对象版本 |
| `deploy/README.md` | 新增「备份与恢复」一节；文件表和「生产部署」第 4、5 步同步 |
| `docs/03-设计/系统详细设计说明书.md` | §2.1 图中的 R2 节点与连线、§9 备份行（拆为 pgBackRest 与逻辑备份两行）、§10 备份行改为 S3；恢复目标写「建议值，待客户确认」；修订记录追加一条 |

接口、表结构、迁移、后端代码：无变更。依赖：仅 ADR-037 已批准的 pgBackRest，版本写死。

## 4. 验证（提示词 §4 的 7 项，全部真实运行）

### 4.1 全量、差异备份成功，WAL 持续归档

首次流程：`stanza-create` → `check` → `pgbackrest-backup.sh full` → 写入 50 行测试数据 → `pgbackrest-backup.sh diff` → `pg_switch_wal()`。脚本均返回 0。`pgbackrest info` 实测输出：

```
stanza: tcrm
    status: ok
    cipher: aes-256-cbc

    db (current)
        wal archive min/max (17): 000000010000000000000001/000000010000000000000008

        full backup: 20261101-233536F
            timestamp start/stop: 2026-11-01 23:35:36+00 / 2026-11-01 23:35:38+00
            wal start/stop: 000000010000000000000004 / 000000010000000000000004
            database size: 29.3MB, database backup size: 29.3MB
            repo1: backup set size: 3.8MB, backup size: 3.8MB

        diff backup: 20261101-233536F_20261101-233545D
            timestamp start/stop: 2026-11-01 23:35:45+00 / 2026-11-01 23:35:47+00
            wal start/stop: 000000010000000000000007 / 000000010000000000000007
            database size: 29.4MB, database backup size: 1.7MB
            repo1: backup set size: 3.8MB, backup size: 180.6KB
            backup reference total: 1 full
```

WAL 归档范围随后一直增长（后续各项测试结束时为 `000000010000000000000001/0000000400000000000000CB`，跨过三次时间线切换）。

附带验证：
- **保留 4 个全量**：连续做到第 5 个全量后，最老的全量（及其差异）被过期，`info` 只剩 4 个全量。
- **口令不泄漏**：用测试口令在 `pg_stat_activity`、`pg_settings`、容器日志、pgBackRest 日志、容器内所有进程命令行中搜索，命中 0 处。`SHOW archive_command` 为 `pgbackrest --stanza=tcrm archive-push %p`；pgBackRest 自己的日志把口令显示为 `<redacted>`。

### 4.2 时间点恢复

用 `restore-pitr.sh --overwrite "<时间>"`，三次，每次 A 在、B 不在：

| 次 | 流程 | 恢复到 | 恢复后 | 备注 |
|---|---|---|---|---|
| 1 | 写 A → 记时间 → 写 B → 切 WAL → 恢复 | `2026-11-01 23:36:10`（脚本补 `+00`） | 有 A，无 B；时间线 2 | |
| 2 | 新全量 → 写 A2 → 记时间 → 写 B2 → 恢复 | `2026-11-01 23:38:03` | 有 A、A2，无 B2；Flyway 记录 91 条原样保留 | |
| 3 | 最终版脚本、非空数据目录 | `2026-11-01 23:56:07` | 有 A、A2、C，无 D | 整个脚本 13 秒（库很小） |

另验证脚本的保护：不加 `--overwrite` 在非空目录上拒绝（返回 1）；输入 `no` 取消（返回 0）；时间格式不对（`tomorrow`）返回 2。

### 4.3 `latest` 恢复与丢失窗口

方法：持续每 5 秒写一行；等到一次 WAL 归档事件后 280 秒时用 `docker kill` 强杀 postgres（模拟崩溃，最后一个未归档的 WAL 段随之丢失），再在一个全新的空数据卷上执行 `restore-pitr.sh latest`（整机丢失场景，无需 `--overwrite`）。

| 实测 | 值 |
|---|---|
| 最后一次 WAL 归档 | 23:43:58（`archive_timeout` 触发的切换） |
| 强杀时间 / 最后一次成功写入 | 23:48:43 / 23:48:43（第 113 行） |
| 恢复后最后一行 | 第 58 行，23:43:53 |
| **丢失窗口** | **290 秒（4 分 50 秒）**，共 55 行；强杀时刻距上次归档 285 秒，已接近 `archive_timeout=300` 的最坏情况 |

结论：丢失窗口不超过 300 秒（约 5 分钟）。恢复到时间线 4，应用能正常启动。

发现一个现象：数据卷还在、但 postgres 是被强杀的，数据目录里会残留 `postmaster.pid`，pgBackRest 拒绝在原目录恢复（`unable to restore while PostgreSQL is running`）。我尝试在脚本里加一步自动删除该文件，执行环境的安全检查拦截了这条命令，我没有绕开，也就没有在脚本里实现。现状：脚本在这种情况下由 pgBackRest 报错退出，README「恢复」一节写明了处理方法（确认容器已停止后手动删除该文件再重新执行）。整机丢失（空卷）不受影响。

### 4.4 逻辑备份加密、解密、`pg_restore`

| 场景 | 结果 |
|---|---|
| 正常运行（`TCRM_BACKUP_S3_BUCKET` 设为测试桶） | 目录里只有 `tcrm-20261101T235043Z.dump.gpg`（81,647 字节），无 `.partial`；`aws` 替身收到 `daily/` 和（当天是周日）`weekly/` 两条上传命令，参数 `--region us-west-2 --only-show-errors` |
| 文件内容 | 开头是 gpg 对称加密包头，搜不到 `PGDMP` 魔数和测试邮箱（0 命中） |
| 错误口令解密 | 返回 2，`gpg: decryption failed: Bad session key` |
| 正确口令解密后送入 `pg_restore`（新库 `t94_restored`） | 返回 0；`t94.t94_marker` 110 行、`flyway_schema_history` 91 行，与在线库一致 |
| 加密步骤失败（gpg 返回 2，此时明文 `.partial` 已存在） | 脚本返回 2，目录里没有任何 `.partial` |
| 加密期间收到 SIGTERM（此时明文 `.partial` 448,405 字节存在） | 脚本返回 143，`.partial` 已删除 |
| 上次被 SIGKILL 的残留明文（模拟 `tcrm-2020…dump.partial`） | 下次运行开始时清除 |
| 保留规则 | 一个超期的 `.dump.gpg` 被删除；`test-retention.sh` 通过 |
| 口令文件权限 644 | 拒绝运行，返回 1 |

### 4.5 恢复后后台启动、Flyway 严格模式

crm-admin 容器的 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS` 为空（严格模式）。恢复后启动日志：

```
DbValidate : Successfully validated 91 migrations
DbMigrate  : Schema "public" is up to date. No migration necessary.
TcrmAdminApplication : Started TcrmAdminApplication in 8.49 seconds
```

`/actuator/health` 返回 `UP`。三次恢复后各验证一次。（过程中有一次启动失败，原因是我自己的测试表建在了 `public` 架构，Flyway 报「非空架构没有历史表」，与备份恢复无关；改放独立架构 `t94` 后重做。）

### 4.6 仓库不可用时 WAL 堆积到上限，不写满磁盘，数据库继续写入

方法：把仓库目录权限改为 000 使其不可用；本机磁盘有限，把 `archive-push-queue-max` 临时调到 64MiB（环境变量覆盖配置文件，机制与生产的 4GiB 相同），连续写入约 2.3 GiB 的 WAL。

| 轮次 | `pg_wal` | 待归档文件数 | 累计产生 WAL | 归档器 成功/失败 |
|---|---|---|---|---|
| 1 | 513MB | 4 | 289 MB | 18/2 |
| 2 | 609MB | 5 | 578 MB | 34/6 |
| 3 | 897MB | 2 | 868 MB | 55/9 |
| 4 | 1025MB | 3 | 1157 MB | 72/16 |
| 5 | 1025MB | 4 | 1447 MB | 89/20 |
| 6 | 1025MB | 4 | 1737 MB | 107/26 |
| 7 | 1025MB | 9 | 2027 MB | 120/30 |
| 8 | 1025MB | 7 | 2317 MB | 141/33 |

- `pg_wal` 稳定在 1025MB（PostgreSQL 自己的 `max_wal_size` 上限），待归档队列始终只有几个文件，不随累计 WAL 增长。
- 队列超限后 pgBackRest 日志出现 `WARN: dropped WAL file '…' because archive queue exceeded 64MB`；写入全程成功，期间插入一行返回成功；postmaster 重启次数 0。
- 仓库权限恢复后归档自动继续，`pgbackrest check` 通过，立即做全量备份成功（缺口之后的时间点无法恢复，由新全量弥补，README 已写明）。
- 生产配置 4GiB：最坏情况 `pg_wal` 约为 4 GiB 队列加 `max_wal_size` 的 1 GiB，README 提醒数据盘预留。4GiB 本身没有在本机实测（磁盘不够），机制与 64MiB 相同。

**发现的问题**：第一次做这个测试时没有 `init: true`。仓库不可用后，postgres 日志出现 `server process (PID n) exited with exit code 103`，随后 `archive command was terminated by signal 3: Quit` 和 `all server processes terminated; reinitializing`，数据库每约 10 秒崩溃恢复一次，写入被中断（客户端报 `terminating connection because of crash of another server process`），`pg_wal` 涨到 529MB 且不受队列上限约束。原因见 §1。给 postgres 服务加 `init: true`（容器 1 号进程变为 `docker-init`）后重做，无重启，行为如上表。

### 4.7 备份文件里搜不到测试用明文邮箱

测试邮箱形如 `t94_plain_email_*@example.com`，共写入 100 多行。

| 搜索范围 | 命中 |
|---|---|
| pgBackRest 仓库卷，共 4654 个文件 | 0 |
| 逻辑备份目录与 S3 替身目录 | 0 |
| 对照：在线数据卷的 `base/`、`pg_wal/`（未加密） | 69，证明搜索方法有效 |

加密生效的另外两个证据：仓库 4654 个文件全部以 OpenSSL 的 `Salted__` 头开头；用错误的 `PGBACKREST_REPO1_CIPHER_PASS` 读取仓库，`pgbackrest info` 报 `FormatError`（无法解析 `backup.info`）。

## 5. 检查命令与结果

以下检查在工作树中直接运行（我本身是子代理，不再向下委派）。不涉及后端和前端，没有运行 `mvn` 和前端检查。

| 命令 | 结果 |
|---|---|
| `docker compose -f deploy/docker-compose.{dev,preview,prod}.yml --env-file deploy/.env.example config -q` | 三个均返回 0 |
| `python3 deploy/scripts/check-healthchecks.py` | 通过（`healthcheck form OK`） |
| `python3 deploy/scripts/check-compose-env.py` | 通过 |
| `bash deploy/backup/test-retention.sh`（主机的 `date` 不支持 `-d`，在 `ubuntu:24.04` 容器里执行） | `retention rule OK` |
| shellcheck 0.10.0（`koalaman/shellcheck:v0.10.0`，`-x`）检查 `pg-backup.sh`、`pgbackrest-backup.sh`、`restore-pitr.sh`、`test-retention.sh` | 无任何提示（`pg-backup.sh` 原有的两条 info 级提示也一并消除） |

## 6. 复核表

| 需求（提示词章节） | 实现位置 | 是否满足 | 说明 |
|---|---|---|---|
| 2.1.1 `deploy/postgres/Dockerfile`，`FROM postgres:17.11`，写死版本 pgBackRest；生产模板暂用 `build:` | `deploy/postgres/Dockerfile`；`docker-compose.prod.yml` postgres `build:` | 满足 | pgBackRest `2.60.0-1.pgdg13+1`，镜像名 `tcrm-postgres:17.11` |
| 2.1.2 `command` 增加 `archive_mode=on`、`archive_timeout=300`、`archive_command` | `docker-compose.prod.yml` postgres `command` | 满足 | 另加 `init: true`（见偏离） |
| 2.1.3 conf：stanza `tcrm`、`aes-256-cbc`、`archive-async`、`archive-push-queue-max=4GiB`、`retention-full=4` | `deploy/postgres/pgbackrest.conf` | 满足 | 验证 4.1、4.6 |
| 2.1.4 全部经 `PGBACKREST_*` 环境变量传入并写进 `.env.example`；支持访问密钥和实例角色 | `docker-compose.prod.yml`、`.env.example` | 满足 | 实例角色用 `KEY_TYPE=auto`；访问密钥用 `shared` 加两个密钥变量。可选变量不能留空（pgBackRest 对空变量报错），compose 用无值透传写法，`.env.example` 把两个密钥行注释掉 |
| 2.1.5 `command`、`.conf` 里无密钥；`pg_stat_activity`、日志里无口令 | 同上 | 满足 | 验证 4.1：五处搜索 0 命中 |
| 2.2 `pgbackrest-backup.sh`：周日全量、其余日差异、退出前 `check`、失败非零；写明 cron 示例和首次 `stanza-create` | `deploy/backup/pgbackrest-backup.sh` | 满足 | 可选参数 `full\|diff` 强制类型。S3 上的实际运行待上线验证 |
| 2.3.1 R2 改 S3：`TCRM_BACKUP_S3_BUCKET`、`_PREFIX`、`AWS_REGION`，无 endpoint，凭证走 AWS CLI 默认链 | `pg-backup.sh`、`.env.example` | 满足 | 上传命令行经 `aws` 替身验证，真实 S3 待上线验证 |
| 2.3.2 校验后 `gpg --symmetric --cipher-algo AES256` 加密，口令文件 600，只留 `.dump.gpg`，`trap` 删明文 | `pg-backup.sh` | 满足 | 验证 4.4；另在运行开头清理上次被 SIGKILL 的残留明文 |
| 2.3.3 保留规则不变，`expired_backups` 与 `test-retention.sh` 同步 | `pg-backup.sh`、`test-retention.sh` | 满足 | `test-retention.sh` 通过；超期文件删除已验证 |
| 2.4.1 打印目标项目、时间、被覆盖内容，输入 `yes`；非空目录默认拒绝，`--overwrite` | `deploy/backup/restore-pitr.sh` | 满足 | 验证 4.2 |
| 2.4.2 停应用与 postgres，一次性容器 `pgbackrest restore`，启动 postgres 等恢复完成，再启动应用 | 同上 | 满足 | 验证 4.2、4.3、4.5。强杀后残留 `postmaster.pid` 需手动删除（见 4.3） |
| 2.4.3 结束打印人工事项：重建链接索引、核对钱包核对日志 | 同上 | 满足 | 另列出：补录恢复点之后的人工操作、立即全量备份、重新登录 |
| 2.4 文档写明逻辑备份恢复命令，含解密 | `pg-backup.sh` 头部、README | 满足 | 解密后管道送入 `pg_restore`，明文不落盘；4.4 用同一流程验证 |
| 2.5.1 README 新增「备份与恢复」：层次、保留期、恢复步骤、演练步骤和核对项（达人、订单、钱包余额、提现记录）、Redis 理由、密钥口令分开存放 | `deploy/README.md` | 满足 | 核对项给了对应表的 SQL（`crm_kol`、`crm_order`、`crm_commission_wallet`、`crm_commission_withdrawal`），表名与字段已在迁移后的库里核对 |
| 2.5.2 设计说明书 §2.1 图 R2、§9、§10 改 S3；恢复目标写「建议值，待客户确认」 | `docs/03-设计/系统详细设计说明书.md` | 满足 | 另追加修订记录。研究文档 `docs/02-调研/部署与运维方案.md` 里的 R2 属历史调研，未动 |
| 2.5.3 `iam-policy.example.json`：仅访问备份桶；写入身份不能改生命周期、关 Object Lock、关复制 | `deploy/backup/iam-policy.example.json` | 满足 | 已按 JSON 解析校验；没有在真实 IAM 上验证，列入待上线验证 |
| 3 不做：不创建真实 S3、IAM、复制、Object Lock；不做 Redis 异地备份、监控告警；不改后端；不改任务 README；不引入 MinIO | — | 满足 | 均未做 |
| 4 七项验证 | 见第 4 节 | 满足 | 全部真实运行 |

## 7. 待上线验证（负责人上线演练时完成）

1. **S3 实际连通**：真实桶、区域、`s3.us-west-2.amazonaws.com` 端点下的 `stanza-create`、`check`、全量与差异备份、WAL 归档；访问密钥（`KEY_TYPE=shared`）与实例角色（`KEY_TYPE=auto`）两种方式各验一次。
2. **逻辑备份上传**：主机上真实的 `aws s3 cp` 到 `daily/`、`weekly/`，以及 `iam-policy.example.json` 在真实账号上能满足上传，同时确认「写入身份」确实不能改生命周期、Object Lock、复制。
3. **S3 防护与 pgBackRest 的配合**：版本控制加 Object Lock 开启后，`pgbackrest expire` 的删除只产生删除标记，确认过期后空间占用符合预期；生命周期规则（`logical/daily` 7 日、`logical/weekly` 28 日、`pgbackrest/` 的保留）与负责人清单一致；跨区域复制生效。
4. **amd64 构建**：本机是 arm64。`pgbackrest=2.60.0-1.pgdg13+1` 的版本号在两种架构下应一致，但没有在 amd64 上实际构建；任务 95 的 CI 构建镜像时会验证。PGDG 只保留较新的版本，所以镜像应构建一次后推送到仓库，不要在服务器上反复构建。
5. **整机恢复耗时**：真实数据量下 `restore-pitr.sh latest` 的耗时与 2 小时目标的对照；本机的 13 秒只说明脚本流程，不代表耗时。
6. **4GiB 队列上限与磁盘余量**：按 README 提示，确认数据盘能容纳 `pg_wal` 的最坏情况。
7. **主机上的 gpg 和 AWS CLI 版本**，以及 cron 下 `pg-backup.sh`、`pgbackrest-backup.sh` 的实际运行。

## 8. 需要负责人决定

1. **恢复目标**：约 5 分钟、2 小时仍是建议值，等待客户确认（待确认清单第 35 项）。
2. **EC2 还是 Lightsail**：实例角色（`KEY_TYPE=auto`）只有 EC2 可用；Lightsail 需要访问密钥。仓库实现两种都支持，但密钥保管方式不同。
3. **Object Lock 模式**（Governance 或 Compliance）和保留天数：影响误删防护强度与存储成本，需与 pgBackRest 的保留（4 个全量）对齐。
4. **数据盘容量**：需按「4 GiB 队列 + 1 GiB max_wal_size + 数据」预留，主机规格由负责人定。
5. **强杀后残留 `postmaster.pid`**：目前文档写明手动删除，要不要让脚本自动处理由负责人定（我尝试时被执行环境的安全检查拦截，未绕开）。

## 9. 偏离与原因

| 偏离 | 原因 |
|---|---|
| `docker-compose.prod.yml` 的 postgres 增加 `init: true`（提示词未要求） | 4.6 实测：没有它，S3 故障会让数据库反复重启。归 §6 之外，但属于「S3 故障时数据库不能受影响」的必要条件 |
| `pg-backup.sh`、`pgbackrest-backup.sh`、`restore-pitr.sh` 读取可选环境变量 `TCRM_COMPOSE_FILE`、`TCRM_ENV_FILE` | 默认值与原来一致；用于非标准目录布局，也让验证可以指向独立的 Compose 项目，不碰生产文件 |
| `pgbackrest-backup.sh` 接受可选参数 `full\|diff` | 首次备份和恢复后需要立即做全量；默认行为就是提示词要求的按星期选择 |
| `restore-pitr.sh` 只在数据目录非空时传 `--delta` | 空目录加 `--delta` 会产生无害但吓人的警告 |
| `PGBACKREST_REPO1_CIPHER_PASS`、`PGBACKREST_REPO1_S3_BUCKET` 在 compose 里不用 `${VAR:?}` | 现有检查用 `.env.example`（这两个值为空）跑 `config -q`，`:?` 会使检查失败。缺失时归档会明确失败（`pgbackrest check`） |
| 检查没有再派子代理 | 我自己就是被派发的子代理，按指示不再向下委派 |

## 10. 遗留与范围外发现（未处理）

1. 设计说明书 §9 写钱包核对「每日 05:00（UTC）」，代码 `CommissionWalletReconcileJob` 的 cron 是 `0 30 2 * * *`（02:30 UTC）。我在 `restore-pitr.sh` 的提示里按代码写 02:30，设计说明书该行未改。
2. `docs/02-调研/部署与运维方案.md`、早期过程稿里仍有 R2 的描述，属历史文档，没有改。
3. 备份监控与告警（归档失败、`pg_wal` 大小、最近一次备份时间）属任务 96。

## 11. 收尾清理

验证项目 `tcrm-t94-verify` 的容器、网络、数据卷（`pgdata`、`pgdata2`、`redisdata`、`pgrepo`）已逐个清理，只对该项目执行 `down -v` 并按名称删除一个残留卷；构建的三个本地镜像（`tcrm-postgres:17.11`、`tcrm-postgres:t94`、`tcrm-t94-verify-crm-admin:t94`）已删除。`tcrm-local`、`tcrm-preview` 的容器和卷没有触碰。`.tmp/` 在 `.gitignore` 里，不进入提交。

如需重新验证，清理命令：

```bash
docker compose -f .tmp/verify/compose.yml --env-file .tmp/verify/env down -v   # 只作用于 tcrm-t94-verify
```

## 12. PR 与 CI

见 PR 描述。CI 状态在推送后看一次，结果记录如下。

- PR：https://github.com/ksws00684315/t-crm/pull/177
- `gh pr checks`（推送后看一次，未轮询）：`Compose files and Dockerfiles` pending，`Vulnerabilities and licenses` pending，其余工作流未触发。最终结果以 PR 页面为准。
