# 117 ops-check.sh 权限修复：执行报告

- 分支：`task/117-ops-check-mode`
- 日期：2026-11-06
- 类型：生产上线阻塞缺陷修复（部署）

## 1. 问题

正式环境安装 RUNBOOK 第 15 章的 root crontab 后，手动执行 `deploy/scripts/ops-check.sh`
报 `Permission denied`。仓库索引中该文件为 `100644`，其余 deploy 脚本均为 `100755`，
crontab 直接调用会失败，主机检查与告警完全不生效。

## 2. 改动

`deploy/scripts/ops-check.sh` 文件模式 `100644` → `100755`。内容不变。

## 3. 验证

| 验证项 | 方式 | 结果 |
|---|---|---|
| 文件模式 | `git ls-files -s deploy/scripts/ops-check.sh` | 待合并后确认 100755 |
| 主机可执行 | 已在生产主机 `chmod +x` 后手动运行 `ops-check.sh` | 输出 `ALERT container: … restarted-within-5m`（发布导致，预期），退出码 0 |
| cron 调用 | 安装 crontab 后每 5 分钟执行 | 待下一轮或合并发布后确认 |

## 4. 复核表

| # | 要求 | 实现 | 状态 |
|---|---|---|---|
| 1 | 脚本可被 cron 直接执行 | 文件模式改为 100755 | 已实现 |
| 2 | 不改变脚本行为 | 只改权限位 | 已实现 |
| 3 | 不影响其他脚本 | 仅一个文件 | 已实现 |

## 5. 偏离与遗留

- 生产主机已手动 `chmod +x` 恢复可用；本 PR 合并后，后续发布不再被重置。
- 该问题在任务 96 交付时未暴露，因为当时通过 `bash ops-check.sh` 或容器方式运行。
