# 任务 136：备份凭证刷新脚本加载 `deploy/.env`（L-112）

> **执行环境**：本机。
> - 工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/136-refresh-creds-env`，分支 `task/136-refresh-creds-env`。
> - **只改 `deploy/`（脚本、测试脚本、README、RUNBOOK、`.env.example` 注释）和本任务的执行报告。不改应用代码，无迁移，不新增依赖。**
> - **不得登录、不得改动正式环境**：不执行 `prod-run.sh`，不改服务器上的 crontab。正式环境的 crontab 由负责人在合并发版后调整。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [`遗留问题清单.md`](遗留问题清单.md) L-112 原文；
> - [`108-生产装机与上线手册-执行报告.md`](108-生产装机与上线手册-执行报告.md) 第 6 节第 1 条（L-112 的来源）；
> - `deploy/backup/refresh-s3-credentials.sh`、`deploy/backup/test-refresh-s3-credentials.sh`；
> - `deploy/scripts/renew-public-cert.sh`、`deploy/scripts/ops-check.sh`：这两个脚本加载 `.env` 的写法；
> - `deploy/README.md`「备份与恢复」、`deploy/RUNBOOK.md` 第 328、437 行附近的调用示例；
> - ADR-037（S3 凭证只用实例角色）。

## 1. 背景

`refresh-s3-credentials.sh` 只读环境变量，不像其他主机脚本那样加载 `deploy/.env`。如果在 cron 里直接调用，会因为缺少 `TCRM_PGBACKREST_CREDS_DIR` 每次都失败，凭证不刷新，WAL 归档和备份也会跟着失败。

任务 108 的绕开办法是：在 RUNBOOK、README 和 crontab 示例里，把 `TCRM_PGBACKREST_CREDS_DIR=...` 写在命令前面传入。正式环境 2026-11-05 起已经上线，实际的 crontab 也是这么写的。

## 2. 要求

1. **脚本加载 `.env`**，与其他主机脚本一致：
   - 默认读取 `deploy/.env`，用脚本自身位置推出路径，不依赖当前目录；`TCRM_ENV_FILE` 可以覆盖。
   - **调用方显式传入的环境变量优先于 `.env`**，这样现有的行内传参写法照样生效，正式环境的 crontab 不用改也能继续工作。写法参照 `ops-check.sh` 的 `preserve_caller_env`。
   - `.env` 不存在时，退回到只读环境变量（现有行为），不报错。
   - 只取脚本需要的变量。不要把 `.env` 的其他内容（例如密钥）导出给后面执行的 `aws` 等子进程；做不到的，在报告里说明原因和影响。
   - 不在任何输出里打印 `.env` 的内容或凭证。
2. **测试**：扩充 `deploy/backup/test-refresh-s3-credentials.sh`，用临时目录和桩 `aws` 覆盖下面四种情况：
   - 只有 `.env`、没有环境变量：能读到 `TCRM_PGBACKREST_CREDS_DIR`；
   - `.env` 和环境变量都有：以环境变量为准；
   - 没有 `.env`：行为与现在相同；
   - `TCRM_ENV_FILE` 指定其他文件：读那个文件。
   - 再加一条：脚本输出里不包含 `.env` 里的其他值，例如在测试 `.env` 中放一个假的 `TCRM_SECRET_KEY`，断言它不出现。
3. **文档改回直接调用**：README、RUNBOOK、脚本头注释里的 crontab 示例和首次运行示例，改为不带行内变量的写法；同时注明「行内传入的变量优先，旧写法仍然有效」。写一段「正式环境 crontab 调整」说明：合并发版后，负责人可以选择改回直接调用，也可以保持现状；写清改法和回退方法。
4. 遵守项目现有的 shell 风格：`set -euo pipefail`，文件模式保持 100755。

## 3. 不做

- 不改 pgBackRest 配置、IAM 策略、备份脚本本身。
- 不改 `release.sh`。L-113 不在本任务范围内。
- 不登录正式环境，不改服务器上的 crontab。
- 不修改 `docs/04-开发任务/README.md`、`遗留问题清单.md`，这些由架构负责人改。

## 4. 验证与交付

- **检查**：只改了 `deploy/` 下的脚本和文档，**不跑 `mvn`，也不跑前端检查**。交给检查子代理执行：
  - `bash -n` 检查改动的脚本；
  - 本机有 `shellcheck` 的就跑一遍，没有的在报告里注明；
  - 运行 `deploy/backup/test-refresh-s3-credentials.sh`，以及 `deploy/scripts/test-ops-check.sh`、`deploy/scripts/test-renew-public-cert.sh`（确认没有连带影响）；
  - `python3 deploy/scripts/check-compose-env.py`（如果改了 `.env.example`）。
- **需求复核**交给只读的复核子代理：按第 2 节逐条核对。
- **执行报告**：`docs/04-开发任务/136-备份凭证刷新脚本加载env-执行报告.md`，附逐条复核表和测试输出。测试输出里不得出现任何凭证，测试用的假值也不要贴进报告。
- **PR**：标题「fix(deploy): 备份凭证刷新脚本加载 deploy/.env（任务 136）」。不合并，不推 main。推送后看一次 `gh pr checks`，结果写进完成后的回复，不轮询。

## 5. 停下来报告

- 发现做不到「行内变量优先」，也就是正式环境现有的 crontab 会因为改动而失效；
- 需要改动 pgBackRest、IAM 或正式环境；
- 现有测试脚本在改动前就已失败。
