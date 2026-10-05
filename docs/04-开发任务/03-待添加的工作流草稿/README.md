# 任务 03 待添加的 GitHub Actions 工作流（草稿）

任务 03 第 4 步要求新增 `admin-ui.yml`、`kol-portal.yml`、`deploy.yml`。执行任务的账号没有 `workflow` 令牌范围，无法推送 `.github/workflows/` 下的文件（提示词禁止为此改动权限），因此这里只存放**已写好的草稿**，请有权限的人执行：

```bash
git mv "docs/04-开发任务/03-待添加的工作流草稿/"{admin-ui,kol-portal,deploy}.yml .github/workflows/
```

草稿特点：

- Action 全部固定到完整提交 SHA（与现有工作流相同的 checkout、其余为 `actions/setup-node` v7.0.0、`pnpm/action-setup` v6.1.0、`docker/setup-buildx-action` v4.4.1、`docker/build-push-action` v7.4.0）；用 actionlint 1.7.12 检查通过。
- 前端：Node 24（当前 LTS）、pnpm 缓存、`pnpm install --frozen-lockfile`、`lint`、`typecheck`、`test`、`build`、`pnpm notices:check`（第三方许可声明与已提交文件一致）、`pnpm audit --audit-level high`。
- 部署：用 `deploy/.env.example` 执行两个 compose 文件的 `docker compose config -q`，运行备份保留规则测试，构建 backend / redirect 两个 Dockerfile（不推送）。
- OSV-Scanner 工作流（`--recursive`）无需修改即会扫描两个前端的 `pnpm-lock.yaml`；本次已用 osv-scanner 2.6.0 在本地用同样的白名单参数验证，见 PR 说明。
