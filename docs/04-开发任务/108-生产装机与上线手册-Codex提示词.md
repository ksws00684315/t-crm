# 任务 108：生产装机与上线手册

> **执行环境**：本机。**只交付文档**（负责人 2026-11-04：实操脚本到正式上线时再做，本机资源紧张）。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/108-production-runbook`，分支 `task/108-production-runbook`，从最新 `main` 检出。
> - **不运行 Docker**：不拉镜像、不启动任何容器或 Compose 项目，不跑 `mvn`、前端检查。不连共享中间件，不运行 `local-infra.sh`、`preview.sh`，不 SSH 到任何主机。不访问真实 AWS、Cloudflare。
> - 不新增、不修改任何脚本、Compose 文件和 CI 工作流。无迁移，无依赖。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 全文；
> - [`生产上线-负责人操作清单.md`](生产上线-负责人操作清单.md) 全文；
> - `deploy/README.md` 中「生产部署」「发布与回滚」「公开证书的申请与续期」「AWS 主机与防火墙」「备份与恢复」「监控告警」「邮件服务」各节；
> - `deploy/.env.example` 全文；
> - `deploy/docker-compose.prod.yml`；`deploy/scripts/release.sh`、`renew-public-cert.sh`、`ops-check.sh`，`deploy/backup/` 下全部脚本（文件头注释和主流程）；
> - 遗留问题清单中 L-061、L-107、L-110 的原文。

## 1. 背景与需求来源

依据：负责人 2026-11-04 决定先补装机手册，再打试发标签、用临时 EC2 演练（负责人操作清单 §6）。

部署脚本（任务 93～96、105、106）都已合并，但上线步骤分散在 `deploy/README.md` 七八个章节和负责人操作清单里，并且缺少主机层面的前置步骤：安装 Docker、AWS CLI、克隆仓库、时区等。crontab 分散在四个章节，`ops-check` 又单独写在一处。任务 106 的审查发现，README 中的一条建目录命令在 Linux 上会让备份全部失败；这类问题要靠按顺序从零执行一遍才会暴露。

本任务产出一份**能从一台空白 EC2 逐条照做到上线**的手册，供负责人在临时 EC2 演练时使用。演练中发现的问题和自动核对脚本，正式上线时再处理。

## 2. 负责人已定的前提（手册按此写，不另设选项）

| 项 | 取值 |
|---|---|
| 系统 | Ubuntu 24.04 LTS，x86_64，us-west-2；机型、磁盘见负责人操作清单 |
| 登录方式 | Session Manager，`sudo -i` 以 root 操作；不开 22 端口 |
| 仓库 | 公开仓库，`git clone https://github.com/ksws00684315/t-crm.git /opt/tcrm`，属主 root；按版本标签检出 |
| 定时任务 | 全部放 root 的 crontab |
| 时区 | 主机设为 UTC |
| 主机目录 | 证书、静态文件、备份、凭证、状态等数据目录全部在仓库之外，统一放 `/var/lib/tcrm/` 下（凭证目录已定为 `/var/lib/tcrm/pgbackrest-creds`）；`.env.example` 中的相对路径默认值保持不变，手册给出生产取值 |

## 3. 要求

### 3.1 手册 `deploy/RUNBOOK.md`（中文）

1. 按执行顺序分章，每一步写明：在哪里做（AWS 控制台、Cloudflare、主机），命令原文，以及怎样确认这一步成功。从空白账号开始，到「上线检查全部通过」为止。至少覆盖：
   - 开机前决定与账号（引用负责人操作清单，不复制）；
   - AWS：EC2（IMDSv2 强制、hop limit 1）、实例角色（S3 策略加上 Session Manager 所需的 `AmazonSSMManagedInstanceCore`，后者当前的清单和 IAM 示例都没写）、安全组、弹性 IP、S3 桶与跨区域复制；
   - 主机初始化：系统更新、时区、Docker Engine 与 Compose 插件（官方 apt 源）、`/etc/docker/daemon.json`、AWS CLI v2（官方安装包并校验签名）、其余脚本在主机上调用到的命令；
   - 获取代码、建目录（逐个写明权限，凭证目录 755）、填写 `.env`（每个必填项怎么生成，文件权限 600）、备份口令文件、外部数据卷、GHCR 登录；
   - 证书：放置 Cloudflare 源站证书，首次申请 Let's Encrypt 证书；
   - 按顺序：首次刷新凭证 → 上线前核对（第 3 项）→ 首次发布 → `stanza-create` 与第一次全量备份 → 平台管理员登录后删除 `TCRM_BOOTSTRAP_ADMIN_PASSWORD`；
   - 定时任务：一个完整的 root crontab 代码块，包含凭证刷新、pgBackRest 备份、逻辑备份、证书续期、`ops-check`，每行上方注释用途；之后首次运行 `ops-check`，确认告警与心跳到达；
   - 上线检查清单：把 README「生产部署」第 7、8 条、负责人操作清单 §6 等分散的检查项合成一张表，每项写确认命令；
   - 恢复演练：步骤引用 README「恢复演练」；手册给出一张演练记录表，记录耗时、丢失窗口、核对项、S3 复制与 Object Lock 的实际效果；
   - 日常运维入口：发布、回滚、告警处理，只写链接。
2. **只写顺序和命令，原理链接 README 对应小节，不复制大段说明。**
3. **上线前核对**：一张人工核对表，每项给出确认命令和期望结果，至少包括：
   - 系统架构 x86_64、时区 UTC；
   - Docker、Compose 版本，`live-restore` 已开启；
   - AWS CLI 是 v2；`aws sts get-caller-identity` 返回 assumed-role；主机上没有 `~/.aws/credentials`，也没有 `AWS_ACCESS_KEY_ID`；
   - `.env` 权限 600，`release.sh` 的 `REQUIRED_ENV` 中各项都有值；
   - 外部数据卷存在；证书文件齐全，私钥权限 600；备份口令文件权限 600；
   - 凭证目录 755，凭证文件属主 uid 999、权限 600；
   - crontab 已安装；
   - postgres、redis、crm-admin、crm-redirect 没有发布端口；
   - 数据盘可用空间满足 README「S3 不可用时」给出的预留量。

   读取 hop limit 需要额外的 IAM 权限，这一项写成控制台核对步骤。
4. **静态核对脚本与文档**：逐个阅读主机上要运行的脚本，只读不运行。列出它们在主机上调用的外部命令（如 `gpg`、`curl`、`flock`、`aws`、`python3`），确认每一个在手册里都有安装步骤，或者 Ubuntu 24.04 自带。核对 README、`.env.example`、负责人操作清单与脚本实际行为是否一致：文档写错的，直接改文档；脚本本身看起来有问题的，**不改脚本**，写进报告。
5. README「生产部署」一节开头加一句，指向手册；README 各处的 cron 示例改为指向手册中的 crontab 代码块。其余章节保留为参考说明。

### 3.2 负责人操作清单

补上实例角色需要的 `AmazonSSMManagedInstanceCore`，并在开头加一句指向手册。其余条目不改。

## 4. 不做

- 不写核对脚本、装机脚本、`crontab.example` 等任何可执行文件（正式上线时再做）。
- 不改脚本、Compose 文件、CI 工作流、IAM 示例策略。
- 不运行 Docker，不访问真实 AWS、Cloudflare，不打标签。
- 不修改 `docs/04-开发任务/README.md` 和遗留问题清单（发现的问题写进报告）。

## 5. 验证（只读，不运行容器）

把方法和结果写进报告：

1. **命令出处核对**：手册中的每条项目内命令（`release.sh`、备份脚本的参数，Compose 服务名，环境变量名，路径），都能在仓库中找到出处；在报告中逐条附上文件和行号。外部软件的安装命令注明所依据的官方文档链接。
2. **盲读复核**：交给一个子代理，只给它 `deploy/RUNBOOK.md` 和负责人操作清单，让它列出读不懂、缺前置条件、顺序有问题或命令无法照抄的地方。逐条处理，把它的原始意见和处理结果写进报告。
3. **一致性复核**：另一个子代理对照 `docker-compose.prod.yml`、`.env.example` 和各脚本，检查手册中的目录、权限、变量、执行顺序有没有与脚本行为矛盾之处。

需要真机才能确认的项目列入手册演练记录表，每项占一行，包括：
- 各条安装命令能否照抄执行；
- Session Manager 登录、实例角色、IMDS hop limit；
- 真实 S3、Let's Encrypt、Cloudflare 回源认证；
- L-061、L-110。

## 6. 验收与交付

- **检查**：只改文档，不跑本地检查。推送后由 CI 运行。
- **执行报告**：`docs/04-开发任务/108-生产装机与上线手册-执行报告.md`，内容包括：
  - 逐条复核表，覆盖 §3 每一项；
  - §5 三项验证的结果；
  - 主机外部命令清单；
  - 文档修正清单；
  - 疑似脚本问题清单。
- **PR**：不合并，不推 main。推送后看一次 `gh pr checks`，结果写进最后回复，不轮询。

## 7. 停下来报告

- 发现某个脚本在 Linux 主机上按现有写法无法工作，手册无法绕开；
- 手册步骤与 ADR-037 或负责人操作清单的决定冲突。
