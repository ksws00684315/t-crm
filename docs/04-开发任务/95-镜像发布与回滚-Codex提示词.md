# 任务 95：镜像发布与回滚

> **执行环境**：本机。
> - **前置**：任务 94 合并后才开始。工作树由架构负责人在派发时创建：`/Users/hh-mini/Public/dev/t-crm/.worktrees/95-image-release`，分支 `task/95-image-release`，从最新 `main` 检出。
> - 不连共享中间件。无迁移。不新增第三方依赖；GitHub Action 用提交 SHA 写死版本（`deploy.yml` 的写法）。
> - 验证用独立 Compose 项目 `tcrm-t95-verify`，端口 `19580`、`19543`。
>
> **必读**（通用阅读范围见 [`本机任务通用要求.md`](本机任务通用要求.md) 第 1 节）：
> - [ADR-037](../06-架构决策/ADR-037-生产部署拓扑与AWS备份容灾.md) 决定 2、7；
> - 规范 08 第 8 节：版本标签 `v{版本}`，首个版本 `1.0.0`；
> - `deploy/README.md` 第 9 项（Flyway 严格模式与应急回滚）；
> - 设计说明书 §12「发布」一行。

**参照实现**：`.github/workflows/deploy.yml`（Action 写法与缓存）、`deploy/scripts/build-static.sh`（静态产物）、`deploy/scripts/preview.sh`（脚本风格）。

## 1. 背景与需求来源

本任务不对应进度表行，依据是 ADR-037（负责人 2026-11-01 确认按方案执行）。现状：`deploy.yml` 只构建不推送，生产模板在服务器上跑 Maven 和前端构建，抢占线上资源，也无法按版本回滚。

## 2. 要求

### 2.1 构建与推送

新增 `.github/workflows/release.yml`，由 `v*` 标签触发，也可手动触发：

1. 构建 `linux/amd64` 的 crm-admin、crm-redirect、postgres 镜像和静态产物镜像，推送到 GHCR，标签与 git 标签一致。权限只开 `contents: read`、`packages: write`。
2. 静态产物：新增 `deploy/static/Dockerfile`，`FROM scratch` 复制 `build-static.sh` 的输出（含 `licenses/`）。
3. 不在发布流程里重复跑全量测试；标签指向的提交必须已通过 `main` 的检查，写进 README。

### 2.2 生产模板

1. crm-admin、crm-redirect、postgres 去掉 `build:`，改为 `image: ${TCRM_IMAGE_REGISTRY}/…:${TCRM_VERSION}`，两个变量进 `.env.example`。文件头的启动命令同步，不再带 `--build`。
2. 前端目录改版本化：`TCRM_STATIC_DIR` 下为 `releases/{版本}/admin|kol` 和相对软链接 `current`。模板 `root` 改为 `/usr/share/nginx/sites/current/...`。Docker 绑定挂载会在挂载时解析软链接，所以挂载的是父目录，不是 `current` 本身。
3. `preview` 模板不受影响，继续本地构建；若 `preview.conf` 的 `root` 无需改动就不要改。

### 2.3 发布脚本

新增 `deploy/scripts/release.sh`：

- `release.sh deploy <版本>`：检查 `.env` 必填项 → 记录当前版本 → 跑一次 `pg-backup.sh`，失败即中止 → 拉取镜像 → 解出静态产物到 `releases/<版本>/` → 更新 crm-admin 并等待 healthy → 仅在镜像摘要变化时更新 crm-redirect → 原子切换 `current`（`ln -sfn` 到临时名后 `mv -T`）→ 更新 `.env` 的 `TCRM_VERSION` → 写发布记录。
- 任一步失败时停止，打印当前状态和下一步建议，不自动回滚数据库。
- crm-admin 不健康时前端不切换。
- `release.sh rollback <版本>`：切回该版本镜像和静态目录。严格模式下 Flyway 拒绝启动时，打印原因和 `deploy/README.md` 的应急方法，**不自动改 `TCRM_FLYWAY_IGNORE_MIGRATION_PATTERNS`**。
- `release.sh status`：显示当前版本、镜像摘要、`current` 指向。
- 只保留最近 5 个静态版本。

前提写进 README：单实例发布有 30～60 秒管理端中断；crm-redirect 重启时跳转短暂不可用，所以发布选在低峰，只改后台的版本不重启 crm-redirect。

### 2.4 文档

1. `deploy/README.md` 的「生产部署」按新流程重写第 3、4 步，新增「发布与回滚」一节。GHCR 登录所需令牌只给 `read:packages`。
2. 设计说明书 §12「发布」一行改为短暂中断的发布流程，删去「滚动重启」。

## 3. 不做

- 不接自动部署到服务器，发布由人在服务器上执行脚本。
- 不做双实例、蓝绿发布。
- 不改应用代码，不改数据库。
- 不打真实版本标签，不推送真实镜像（可用临时本地 registry 容器验证，`registry:2` 写死版本）。
- 不修改 `docs/04-开发任务/README.md`。

## 4. 验证

用仓库外的 override（放工作树 `.tmp/`，不提交）启动项目 `tcrm-t95-verify`，只允许删除该项目自己的容器和卷。用本地 registry 模拟 GHCR，构建两个假版本（前端带不同标记）。必须验证：

1. `deploy` 1.0.0 全流程成功。
2. `deploy` 1.0.1：期间持续请求 `t./healthz`，切换前端全程无失败、Nginx 未重启；只改后台时 crm-redirect 容器 ID 不变。
3. 让 crm-admin 启动失败的版本：前端不切换，脚本报错退出。
4. 备份失败时发布中止。
5. `rollback` 无新迁移时成功；有新迁移时 Flyway 拒绝启动，脚本打印指引，没有改动环境变量。
6. 工作流文件过 `actionlint`（未安装时用官方容器）。

## 5. 验收与交付

- **检查**：部署三项检查，脚本过 shellcheck。不跑 `mvn`、前端检查。交给子代理。
- **浏览器验收**：做一次 `current` 切换前后的页面打开确认，截图放 `docs/04-开发任务/assets/task-95/`。
- **执行报告**：`docs/04-开发任务/95-镜像发布与回滚-执行报告.md`，逐条复核表覆盖 §2 每一项。
- **PR**：不合并、不推 main。推送后看一次 `gh pr checks`，结果写进报告，不轮询。

## 6. 停下来报告

- 软链接方案下 Nginx 读不到文件，或切换时出现失败请求；
- 严格模式与发布脚本的预期不一致；
- 需要改应用代码才能完成。
