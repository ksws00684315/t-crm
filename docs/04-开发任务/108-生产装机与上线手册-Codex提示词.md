# 任务 108：生产装机与上线手册

> **执行环境**：本机。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/108-production-runbook`，分支 `task/108-production-runbook`，从最新 `main` 检出。
> - 不连共享中间件，不启动后端开发进程，不运行 `deploy/scripts/local-infra.sh` 和 `preview.sh`（L-108）。无迁移。仓库不新增依赖。验证用的镜像只在验证环境使用，不进仓库：必须是宽松许可证，并写死版本。
> - 验证用独立 Compose 项目 `tcrm-t108-verify`，端口 `19980`、`19943`，收尾时自己删除。不得碰 `tcrm-preview` 和 `tcrm-local`。
> - 不访问真实 AWS、Cloudflare、GHCR 写操作。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 全文；
> - [`生产上线-负责人操作清单.md`](生产上线-负责人操作清单.md) 全文；
> - `deploy/README.md` 中「生产部署」「发布与回滚」「公开证书的申请与续期」「AWS 主机与防火墙」「备份与恢复」「监控告警」「邮件服务」各节；
> - `deploy/.env.example` 全文；
> - `deploy/scripts/release.sh`、`renew-public-cert.sh`、`ops-check.sh`，`deploy/backup/` 下全部脚本的文件头注释；
> - 遗留问题清单中 L-061、L-107、L-110 的原文。

**参照实现**：`deploy/scripts/ops-check.sh` 的检查函数与 `test-ops-check.sh` 的测试写法（新脚本照这个写）；任务 95 执行报告中发布脚本的端到端验证方式。

## 1. 背景与需求来源

依据：负责人 2026-11-04 决定先补装机手册，再打试发标签、用临时 EC2 演练（负责人操作清单 §6）。

部署脚本（任务 93～96、105、106）都已合并，但上线步骤分散在 `deploy/README.md` 七八个章节和负责人操作清单里，并且缺少主机层面的前置步骤：安装 Docker、AWS CLI、克隆仓库、时区等。crontab 分散在四个章节，`ops-check` 又单独写在一处。任务 106 的审查发现，README 中的一条建目录命令在 Linux 上会让备份全部失败；这类问题只有按顺序从零执行一遍才会暴露。

本任务产出一份**能从一台空白 EC2 逐条照做到上线**的手册，以及配套的核对脚本，供负责人在临时 EC2 上演练时使用。

## 2. 负责人已定的前提（手册按此写，不另设选项）

| 项 | 取值 |
|---|---|
| 系统 | Ubuntu 24.04 LTS，x86_64，us-west-2；机型、磁盘见负责人操作清单 |
| 登录方式 | Session Manager，`sudo -i` 以 root 操作；不开 22 端口 |
| 仓库 | 公开仓库，`git clone https://github.com/ksws00684315/t-crm.git /opt/tcrm`，属主 root；按版本标签检出 |
| 定时任务 | 全部放 root 的 crontab |
| 时区 | 主机设为 UTC |
| 主机目录 | 证书、静态文件、备份、凭证、状态等数据目录全部在仓库之外，统一放到 `/var/lib/tcrm/` 下（凭证目录已定为 `/var/lib/tcrm/pgbackrest-creds`）；`.env.example` 中的相对路径默认值保持不变，手册给出生产取值 |

## 3. 要求

### 3.1 手册 `deploy/RUNBOOK.md`（中文）

1. 按执行顺序分章，每一步写明：在哪里做（AWS 控制台、Cloudflare、主机）、命令原文、怎样确认这一步成功。从空白账号开始，到「上线检查全部通过」为止。至少覆盖：
   - 开机前决定与账号（引用负责人操作清单，不复制）；
   - AWS：EC2（IMDSv2 强制、hop limit 1）、实例角色（S3 策略 + Session Manager 所需的 `AmazonSSMManagedInstanceCore`，后者当前的清单和 IAM 示例都没写，要补到负责人操作清单）、安全组、弹性 IP、S3 桶与跨区域复制；
   - 主机初始化：系统更新、时区、Docker Engine 与 Compose 插件（官方 apt 源，记录验证时的版本号）、`/etc/docker/daemon.json`、AWS CLI v2（官方安装包并校验签名）、其余脚本在主机上调用到的命令；
   - 获取代码、建目录（权限逐个写明，凭证目录 755）、填写 `.env`（每个必填项怎么生成，权限 600）、备份口令文件、外部数据卷、GHCR 登录；
   - 证书：Cloudflare 源站证书放置、Let's Encrypt 首次申请；
   - 凭证首次刷新 → 核对脚本 → 首次发布 → `stanza-create` 与第一次全量备份 → 平台管理员登录后删除 `TCRM_BOOTSTRAP_ADMIN_PASSWORD`；
   - 安装 crontab、首次运行 `ops-check`、确认告警与心跳到达；
   - 上线检查清单：把 README「生产部署」第 7、8 条、负责人操作清单 §6 等分散的检查项合成一张表，每项写确认命令；
   - 恢复演练：步骤引用 README「恢复演练」，手册给出一张演练记录表（耗时、丢失窗口、核对项、S3 复制与 Object Lock 实际效果）；
   - 日常运维入口：发布、回滚、告警处理，只写链接。
2. **只写顺序和命令，原理链接 README 对应小节，不复制大段说明。** README 与手册说法不一致时，以脚本实际行为为准，改正错误的一方，并在报告里逐条列出。
3. 逐个读一遍主机上要运行的脚本（`release.sh`、`renew-public-cert.sh`、`ops-check.sh`、`deploy/backup/*.sh`），列出它们在主机上调用的外部命令（如 `gpg`、`curl`、`flock`、`aws`、`python3`），确认每一个在手册里都有安装步骤，或 Ubuntu 24.04 自带。清单写进报告。
4. README「生产部署」一节开头加一句，指向手册；其余章节保留为参考说明。

### 3.2 `deploy/crontab.example`

全部 root 定时任务放在一个文件里：凭证刷新、pgBackRest 备份、逻辑备份、证书续期、`ops-check`。每行上方一行注释说明用途和对应的 README 小节。README 各处的 cron 示例改为指向这个文件，不再各写一份。

### 3.3 核对脚本 `deploy/scripts/preflight.sh`

只读检查，不改主机上任何东西。首次发布前、演练前各运行一次。逐项输出 `OK` / `FAIL` / `WARN`，有 `FAIL` 时非零退出。至少检查：

1. 系统是 x86_64、时区是 UTC；
2. Docker Engine、Compose 插件存在，版本不低于验证时使用的版本（最低版本写成常量，注明依据）；`daemon.json` 开了 `live-restore`；
3. AWS CLI 是 v2；`aws sts get-caller-identity` 返回的是实例角色（assumed-role）；主机上存在 `~/.aws/credentials` 或 `AWS_ACCESS_KEY_ID` 时 `FAIL`（ADR-037 决定 1）；
4. `deploy/.env` 存在、权限 600，必填项都有值。**必填项清单复用 `release.sh` 的 `REQUIRED_ENV`，不另抄一份**；为此可以对 `release.sh` 做最小改动（例如让它可被 source），行为不变，`test-release-lock.sh` 仍通过；
5. `TCRM_BOOTSTRAP_ADMIN_PASSWORD` 仍在 `.env` 中时 `WARN`（首次发布需要它，登录后应删除）；
6. 外部数据卷存在；证书文件齐全，私钥权限不宽于 600；备份口令文件存在且权限 600；
7. 凭证目录权限 755；`PGBACKREST_REPO1_TYPE` 不是 `posix` 时，凭证文件存在、属主 uid 999、权限 600；
8. root 的 crontab 包含 `crontab.example` 中的每一条（忽略空白差异）；
9. 生产 Compose 配置中 postgres、redis、crm-admin、crm-redirect 没有发布端口；
10. 数据盘可用空间不低于 README「S3 不可用时」给出的预留量（取值写成常量，注明依据）。

检查只用主机已有的命令和实例角色已有的权限；需要额外 IAM 权限的检查（例如读取实例的 hop limit）不做，改为手册中的控制台核对步骤。判断逻辑写成可 source 的函数，新增 `deploy/scripts/test-preflight.sh` 覆盖每项的通过与失败，并加入 `.github/workflows/deploy.yml`。

### 3.4 负责人操作清单

补上实例角色需要的 `AmazonSSMManagedInstanceCore`，并在开头加一句指向手册。其余条目不改。

## 4. 不做

- 不改发布、备份、恢复、监控脚本的行为（§3.3 第 4 项允许的最小改动除外）。
- 不写自动装机脚本，安装步骤以手册命令为准。
- 不改 hop limit、IAM 示例策略的 S3 部分、Object Lock 设置。
- 不访问真实 AWS、Cloudflare；不打版本标签；不推送任何镜像。
- 不修改 `docs/04-开发任务/README.md` 和遗留问题清单（发现的问题写进报告）。

## 5. 验证

把命令和结果写进报告：

1. **主机初始化命令**：在 `ubuntu:24.04`（`--platform linux/amd64`）容器里，按手册原文逐条执行「主机初始化」一章的命令，全部成功。容器里无法执行的步骤（如 `systemctl`、`timedatectl`）逐条列出并说明替代确认方法。记录装上的 Docker、Compose、AWS CLI 版本，与 `preflight.sh` 的最低版本常量一致。
2. **核对脚本**：`test-preflight.sh` 全部通过（macOS 与 Linux 容器各跑一次，Linux 用 `postgres:17.11` 或 `ubuntu:24.04`）。另在 `tcrm-t108-verify` 上实跑：配置正确时全部 `OK`；分别制造凭证目录 700、`.env` 权限 644、缺一条 crontab、缺证书文件、postgres 发布端口，每种都 `FAIL` 并给出可读原因。AWS 相关检查用桩替代 `aws`。
3. **按手册走一遍**：参照任务 95 的端到端方式（镜像本地构建，必要时用本地镜像仓库替身），在 `tcrm-t108-verify` 上从「获取代码」之后的步骤走到首次发布成功、`stanza-create` 与全量备份成功（posix 仓库）、`ops-check` 首次运行。偏离手册原文的每一处都要回头改手册，或在报告中说明为什么本机无法按原文执行。
4. **盲读复核**：交给一个只拿到 `deploy/RUNBOOK.md`、`deploy/crontab.example` 和负责人操作清单的子代理，让它列出读不懂、缺前置条件、顺序有问题或命令无法照抄的地方。逐条处理，把它的原始意见和处理结果写进报告。
5. `crontab.example` 中每条命令指向的脚本都存在且可执行；用 `crontab -l` 格式校验（在 Linux 容器中 `crontab crontab.example && crontab -l`）。

只能在真实 EC2 上确认的项目（Session Manager 登录、实例角色、IMDS hop limit、真实 S3、Let's Encrypt、Cloudflare 回源认证、L-061、L-110）列入「待上线验证」，并在手册的演练记录表中各占一行。

## 6. 验收与交付

- **检查**：部署三项检查，`test-retention.sh`、`test-ops-check.sh`、`test-refresh-s3-credentials.sh`、`test-release-lock.sh`、`test-renew-public-cert.sh`、`test-preflight.sh`；新脚本和改动的脚本过 shellcheck（本机没有就用容器跑）。不跑 `mvn`、前端检查。交给子代理。
- **浏览器验收**：不涉及界面，不做。
- **执行报告**：`docs/04-开发任务/108-生产装机与上线手册-执行报告.md`，逐条复核表覆盖 §3 每一项，§5 五项验证写明命令和结果，附主机外部命令清单、README 与手册不一致的修正清单。
- **PR**：不合并、不推 main。推送后看一次 `gh pr checks`，结果写进最后回复，不轮询。

## 7. 停下来报告

- 发现某个脚本在 Linux 主机上按现有写法无法工作（类似任务 106 的目录权限问题），需要改脚本行为才能继续；
- 复用 `REQUIRED_ENV` 需要对 `release.sh` 做超出「可被 source」的改动；
- 某项必要检查必须新增 IAM 权限或新增依赖才能实现；
- 手册步骤与 ADR-037 或负责人操作清单的决定冲突。
