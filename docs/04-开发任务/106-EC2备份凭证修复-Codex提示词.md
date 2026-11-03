# 任务 106：EC2 备份凭证修复

> **执行环境**：本机。
> - **前置**：任务 105 合并后才开始。105 也在改 `docker-compose.prod.yml` 的 postgres 服务、`ops-check.sh`、`restore-pitr.sh`、pgBackRest 日志。工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/106-ec2-backup-credentials`，分支 `task/106-ec2-backup-credentials`，从最新 `main` 检出。
> - 不连共享中间件，不启动本地后端开发进程。无迁移。不新增依赖。验证用的 S3 替身只在验证环境中使用，不进仓库：必须是宽松许可证，并写死版本；不得用 MinIO（AGPL）。
> - 验证用独立 Compose 项目 `tcrm-t106-verify`，端口 `19880`、`19843`，收尾时自己删除。不得碰 `tcrm-preview` 和 `tcrm-local`。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 1、5：主机用 EC2，使用实例角色，主机上不放 AWS 访问密钥；强制 IMDSv2，hop limit 为 1；
> - [`遗留问题清单.md`](遗留问题清单.md) 中 L-107 的原文，以及 L-012 的原文（单文件挂载被原子替换后，容器看到的还是旧文件）；
> - 任务 94 执行报告 §4.6（`init: true` 的原因）、§7 第 1 条；
> - `deploy/README.md`「备份与恢复」「监控告警」两节。

**参照实现**：`deploy/backup/pg-backup.sh`（脚本结构、可 source 的函数）、`deploy/scripts/ops-check.sh` 中的 `check_cert`、`check_logical_backup`（新增检查照这两个写）、`deploy/scripts/test-ops-check.sh`（测试写法）。

## 1. 背景与需求来源

依据：L-107（架构负责人 2026-11-03 审查任务 94～96 时发现，负责人同意修复）。

任务 94 实现时主机类型还没定，pgBackRest 在 postgres 容器里用 `PGBACKREST_REPO1_S3_KEY_TYPE=auto` 自己去实例元数据（IMDS）取实例角色凭证。负责人确定使用 EC2 后，主机按清单强制 IMDSv2、hop limit 设为 1，容器经过 Docker 网桥多一跳，读不到实例元数据。结果是：

- WAL 归档、全量和差异备份全部失败；
- 归档队列达到 `archive-push-queue-max` 后，pgBackRest 丢弃 WAL，期间无法做时间点恢复。

主机上 `pg-backup.sh` 通过 AWS CLI 上传逻辑备份，不经过容器，不受影响。

## 2. 要求

### 2.1 凭证写入（架构负责人决定，不放宽 hop limit）

新增 `deploy/backup/refresh-s3-credentials.sh`，在主机上以 root 运行（host cron 每 15 分钟一次）：

1. 用主机上已有的 AWS CLI v2 从默认凭证链取得临时凭证，推荐用 `aws configure export-credentials`。取到的凭证必须带过期时间（即来自实例角色）；没有过期时间时报错退出，不能把长期访问密钥写进文件。
2. 剩余有效期少于 30 分钟时报错退出，不写文件。
3. 写成 pgBackRest 配置片段，内容只有：`[global]`、`repo1-s3-key-type=shared`、`repo1-s3-key`、`repo1-s3-key-secret`、`repo1-s3-token`。
4. 原子写入：先在同一目录写临时文件，设好属主和权限，再 `mv`。文件属主是镜像内 postgres 用户的 uid（先在镜像里确认实际值），权限 600。
5. 失败时保留旧文件，非零退出，错误信息里不出现任何凭证内容。脚本不打开 `set -x`。
6. 目录由环境变量 `TCRM_PGBACKREST_CREDS_DIR` 指定，进 `.env.example`。目录必须在仓库外。

### 2.2 挂载与配置

1. 把 `TCRM_PGBACKREST_CREDS_DIR` **整个目录**只读挂载到 postgres 容器的 `/etc/pgbackrest/conf.d`（pgBackRest 默认的 `config-include-path`，先确认 2.60 版本仍是这个默认值）。不要单文件挂载，原因见 L-012。
2. pgBackRest 的环境变量优先级高于配置文件。生产 Compose 中删除 `PGBACKREST_REPO1_S3_KEY_TYPE`、`PGBACKREST_REPO1_S3_KEY`、`PGBACKREST_REPO1_S3_KEY_SECRET` 三项传递，`.env.example` 中删除这三行及「二选一」的说明。改动后的生产模板只保留实例角色这一种方式（ADR-037 决定 1）。
3. `deploy/postgres/pgbackrest.conf` 文件头的注释同步。
4. `restore-pitr.sh`、`pgbackrest-backup.sh` 都经过同一个 postgres 服务运行，检查它们在新挂载下照常工作。不另加挂载。
5. 本地 posix 仓库的验证方式（任务 94 的做法）不能被破坏：posix 仓库时凭证目录可以为空。

### 2.3 监控

`ops-check.sh` 新增一项：凭证文件最后修改时间超过 60 分钟，或文件不存在，告警「备份凭证未刷新」。阈值可用 `TCRM_OPS_*` 覆盖，与现有阈值写法一致。在 `test-ops-check.sh` 中补用例。

### 2.4 文档

1. `deploy/README.md`「备份与恢复」：凭证一节改为只有实例角色这一种方式，写明原理（hop limit 为 1 时容器读不到实例元数据，由主机写文件）、cron 行，以及首次启用顺序：先建目录、跑一次刷新脚本，再启动 postgres。
2. 删掉 README 和 `.env.example` 中关于 `KEY_TYPE=auto` 的说法。
3. 任务 94 的执行报告是历史记录，不改。

## 3. 不做

- 不改 hop limit，不让任何容器访问实例元数据。
- 不保留访问密钥方式。
- 不改应用代码，不改 pgBackRest 版本，不改备份保留规则。
- 不访问真实 AWS。
- 不修改 `docs/04-开发任务/README.md`。

## 4. 验证

用仓库外的 override（放工作树 `.tmp/`，不提交）启动 `tcrm-t106-verify`。S3 用本地替身，测试时可关闭 TLS 校验，但该设置只写在 override 里。AWS CLI 用桩输出临时凭证，同一个桩能模拟「过期时间不足」和「没有过期时间」。只允许删除这个项目自己的容器和卷。

必须逐项验证，把命令和结果写进报告：

1. 不设 `KEY_TYPE=auto`、只靠凭证文件：`stanza-create`、`check`、全量备份、WAL 归档全部成功。
2. **轮换不重启**：postgres 运行中换一组凭证（替身同时接受新旧两组，或在请求里记录 `x-amz-security-token`），之后的归档请求带的是新令牌，postgres 没有重启。报告写明用什么方法确认请求里的令牌。
3. **原子性**：刷新脚本连续快速执行 100 次，同时持续写库触发归档，没有一次读到半个文件或解析失败。
4. 刷新失败的三种情况（桩报错、过期时间不足、没有过期时间）：旧文件保留，退出码非 0，标准输出、标准错误和文件里都搜不到桩里的密钥字符串。
5. `restore-pitr.sh latest` 在新挂载下恢复成功，后台能启动。
6. 用一个容器访问 `169.254.169.254` 的结果不受本任务影响（本机无 IMDS，只确认 Compose 中没有新增 host 网络或额外的路由配置）。
7. `ops-check` 的凭证检查：文件过旧、文件缺失时告警，恢复后发恢复通知。
8. posix 仓库在凭证目录为空时仍能按任务 94 的方式工作。

真实 EC2 上实例角色经 AWS CLI 取凭证、S3 实际归档，列入「待上线验证」。

## 5. 验收与交付

- **检查**：部署三项检查，`bash deploy/backup/test-retention.sh`，`bash deploy/scripts/test-ops-check.sh`，新脚本和改动的脚本过 shellcheck。不跑 `mvn`、前端检查。交给子代理。
- **浏览器验收**：不涉及界面，不做。
- **执行报告**：`docs/04-开发任务/106-EC2备份凭证修复-执行报告.md`，逐条复核表覆盖 §2 每一项。
- **遗留清单**：不改状态，由架构负责人在合并时更新。
- **PR**：不合并、不推 main。推送后看一次 `gh pr checks`，结果写进报告，不轮询。

## 6. 停下来报告

- pgBackRest 2.60 不支持 `repo1-s3-token`，或不从 `conf.d` 读取配置，或长时间运行的异步归档进程不会重新读取配置；
- AWS CLI v2 拿不到带过期时间的临时凭证，又没有不新增依赖的办法；
- 找不到许可证合规、又能接受临时凭证的 S3 替身，§4 第 1、2 项无法验证；
- 需要改应用代码或已合并的迁移。
