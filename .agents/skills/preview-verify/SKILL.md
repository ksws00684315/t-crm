---
name: preview-verify
description: 任务改动了 application.yml 配置项或环境变量、deploy/ 下的 Compose / Nginx / 脚本、或启动时的初始化逻辑时使用，用 preview.sh 起一套独立预览实际启动验证。Use when config, deploy files or startup logic change.
---

# 用本地预览实际启动

依据：规范 12 第 6 节、`本机任务通用要求.md` 第 4 节第 4 条、`deploy/README.md`「本地预览」。

## 什么时候必须做

PR 改动了以下任一内容：

- `application.yml` 中的配置项或环境变量占位符；
- `deploy/` 下的 Compose 文件、Nginx 配置、脚本；
- 启动时执行的初始化逻辑（例如读取配置、写 Redis）。

单元测试和集成测试的配置自带这些值，查不出部署文件漏传变量（任务 34 因此无法启动，#87 修复）。

## 步骤

1. 先跑静态检查：
   ```bash
   docker compose -f deploy/docker-compose.preview.yml --env-file deploy/.env.example config -q
   python3 deploy/scripts/check-healthchecks.py
   python3 deploy/scripts/check-compose-env.py
   ```
2. 在工作树根目录起一套**独立**预览，项目名和端口用任务分配的，**不要动负责人的 `tcrm-preview`**：
   ```bash
   TCRM_PREVIEW_PROJECT=tcrm-p{编号} \
   TCRM_PREVIEW_ADMIN_PORT=<分配的端口> \
   TCRM_PREVIEW_KOL_PORT=<分配的端口> \
   TCRM_PREVIEW_REDIRECT_PORT=<分配的端口> \
   deploy/scripts/preview.sh up
   ```
   网段冲突时加 `TCRM_PREVIEW_SUBNET=<未占用网段>`。
3. 确认后台、跳转服务都能启动，三个入口返回 200；后端健康但验证码返回 502 时重新执行一次 `up`。
4. 验证完删除自己这套预览：`TCRM_PREVIEW_PROJECT=tcrm-p{编号} deploy/scripts/preview.sh reset`（删除本预览项目的容器和本地镜像，须输入 yes，不改共享数据）。只删自己创建的资源，不执行 `docker system prune`。
5. 执行报告写明启动命令、三个入口的返回码和清理命令。
