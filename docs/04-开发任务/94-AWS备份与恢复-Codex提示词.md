# 任务 94：AWS 备份与恢复

> **执行环境**：本机。
> - **前置**：任务 93 合并后才开始。工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/94-aws-backup`，分支 `task/94-aws-backup`，从最新 `main` 检出。
> - 不连共享中间件。无迁移。依赖：pgBackRest（MIT，ADR-037 已批准），版本写死。
> - 验证用独立 Compose 项目 `tcrm-t94-verify`，端口 `19480`、`19443`。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 5、6；
> - 设计说明书 §9「数据库备份」行、§10「备份」行；
> - `deploy/README.md`「资源与 Redis 容量」中 Redis 丢失后的恢复方法；
> - 规范 06 中密钥与日志的要求。

**参照实现**：`deploy/backup/pg-backup.sh`、`deploy/backup/test-retention.sh`、`deploy/scripts/local-infra.sh` 的 `restore` 确认方式（输入 yes）。

## 1. 背景与需求来源

本任务不对应进度表行，依据是 ADR-037（负责人 2026-11-01 确认按方案执行）。现状：只有每日 `pg_dump`，不加密，R2 上传默认关闭，最多丢一天数据。人工归因、提现付款无法从微店补拉，所以要连续归档。

## 2. 要求

### 2.1 PostgreSQL 镜像

1. 新增 `deploy/postgres/Dockerfile`：`FROM postgres:17.11`，安装写死版本的 pgBackRest。生产模板 postgres 服务暂用 `build:`，任务 95 再改成拉取镜像。
2. `command` 增加：`archive_mode=on`、`archive_timeout=300`、`archive_command` 指向 pgBackRest。
3. 配置文件 `deploy/postgres/pgbackrest.conf`：stanza 固定 `tcrm`；`repo1-cipher-type=aes-256-cbc`；`archive-async` 视需要开启；`archive-push-queue-max=4GiB`，S3 不可用时不能写满磁盘；`repo1-retention-full=4`。
4. 仓库类型、桶、区域、路径、密钥、加密口令全部经环境变量传入（pgBackRest 原生 `PGBACKREST_*`），并写进 `.env.example`。支持访问密钥和实例角色两种取得方式，不写死其中一种（ADR-037 决定 1）。
5. 不要在 `command` 或 `.conf` 里写任何密钥。`pg_stat_activity`、日志里不能出现口令。

### 2.2 定时备份

新增 `deploy/backup/pgbackrest-backup.sh`：周日全量，其余日差异备份，退出前跑一次 `pgbackrest check`。失败时非零退出。写明 cron 示例和首次 `stanza-create` 的方法。

### 2.3 逻辑备份

改造 `pg-backup.sh`：

1. 备份目标由 R2 改为 S3：`TCRM_R2_*` 改为 `TCRM_BACKUP_S3_BUCKET`、`TCRM_BACKUP_S3_PREFIX`、`AWS_REGION`；不再需要 endpoint。凭证留给 AWS CLI 默认链取得。
2. 校验通过后用 `gpg --symmetric --cipher-algo AES256` 加密，口令来自 `TCRM_BACKUP_PASSPHRASE_FILE`（权限 600）。本机和 S3 都只保留 `.dump.gpg`，明文 `.partial` 在任何退出路径都要删除（`trap`）。
3. 保留规则不变（7 日 + 4 周），`expired_backups` 与 `test-retention.sh` 随文件名同步。

### 2.4 恢复脚本

新增 `deploy/backup/restore-pitr.sh <时间点|latest>`：

1. 打印目标项目、目标时间、将被覆盖的内容，输入 `yes` 才继续；生产数据目录非空时默认拒绝，需显式 `--overwrite`。
2. 停 crm-admin、crm-redirect、postgres，用一次性容器执行 `pgbackrest restore`，再启动 postgres 等待恢复完成，然后启动应用。
3. 结束时打印必须人工完成的事：登录超管点「重建链接索引」、核对钱包核对日志。

文档写明逻辑备份的恢复命令，包括解密。

### 2.5 文档

1. `deploy/README.md` 新增「备份与恢复」一节：备份层次、保留期、恢复步骤、演练步骤和核对项（达人、订单、钱包余额、提现记录）、Redis 不做异地备份的理由、主密钥和备份口令必须与备份分开存放。
2. 设计说明书 §2.1 图中的 R2、§9、§10 按 ADR-037 改为 S3。恢复目标写「建议值，待客户确认」。
3. 给出 IAM 最小权限策略示例 `deploy/backup/iam-policy.example.json`：只能访问备份桶，备份写入身份不授予修改生命周期、关闭 Object Lock 或关闭复制的权限。

## 3. 不做

- 不创建真实 S3 桶、IAM、跨区域复制和 Object Lock，这些在负责人操作清单里。
- 不做 Redis 异地备份。
- 不做备份监控告警（任务 96）。
- 不改后端代码。不修改 `docs/04-开发任务/README.md`。
- 不引入 MinIO（AGPL，规范 07）。

## 4. 验证

本地没有 S3：pgBackRest 用 `repo1-type=posix`，仓库放临时卷。

用仓库外的 override（放工作树 `.tmp/`，不提交）启动项目 `tcrm-t94-verify`，只允许删除该项目自己的容器和卷。必须验证：

1. 全量、差异备份成功，WAL 持续归档，`pgbackrest info` 输出写进报告。
2. **时间点恢复**：写入标记行 A → 记下时间 → 写入行 B → 恢复到该时间 → A 在、B 不在。
3. `latest` 恢复：等一次 `archive_timeout` 后恢复，确认丢失窗口不超过约 5 分钟，记录实测值。
4. 逻辑备份加密、解密、`pg_restore` 全流程，确认目录下没有明文残留，模拟中途失败也没有。
5. 恢复后后台能启动，Flyway 严格模式校验通过。
6. 仓库不可用时 WAL 堆积到上限后不写满磁盘，数据库继续写入。
7. 备份文件里搜不到测试用的明文邮箱（确认加密生效）。

S3 实际连通列入「待上线验证」，负责人上线演练时完成。

## 5. 验收与交付

- **检查**：部署三项检查，`bash deploy/backup/test-retention.sh`，新脚本过 shellcheck。不跑 `mvn`、前端检查。交给子代理。
- **浏览器验收**：不涉及界面，不做。
- **执行报告**：`docs/04-开发任务/94-AWS备份与恢复-执行报告.md`，逐条复核表覆盖 §2 每一项。
- **PR**：不合并、不推 main。推送后看一次 `gh pr checks`，结果写进报告，不轮询。

## 6. 停下来报告

- 官方 `postgres:17.11` 镜像的软件源里拿不到可写死版本的 pgBackRest；
- 无法做到“S3 故障不写满磁盘”；
- 时间点恢复验证不通过；
- 需要改后端代码或已合并的迁移。
